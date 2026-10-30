package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
)

type ccapiEndpoint struct {
	Path string
	Get  bool
	Put  bool
	Post bool
}

// ccapiResources chooses versions per operation, rather than assuming that the
// newest API version supplies every endpoint or that every body allows writes.
type ccapiResources map[string]string

func discoverCCAPI(ctx context.Context, client *ccapiHTTP) (ccapiResources, error) {
	var response map[string]json.RawMessage
	if err := client.json(ctx, http.MethodGet, "/ccapi", nil, &response); err != nil {
		return nil, err
	}
	resources := make(ccapiResources)
	versions := make(map[string]int)
	for version, raw := range response {
		if !strings.HasPrefix(version, "ver") {
			continue
		}
		number, err := strconv.Atoi(strings.TrimPrefix(version, "ver"))
		if err != nil || number < 100 {
			continue
		}
		var endpoints []ccapiEndpoint
		if err := json.Unmarshal(raw, &endpoints); err != nil {
			return nil, errors.New("相机CCAPI资源列表格式无效")
		}
		prefix := "/ccapi/" + version + "/"
		for _, endpoint := range endpoints {
			u, err := client.resource(endpoint.Path)
			if err != nil || u.RawQuery != "" || !strings.HasPrefix(u.Path, prefix) {
				return nil, errors.New("相机CCAPI资源不属于已公布的版本")
			}
			suffix := strings.TrimPrefix(u.Path, prefix)
			for method, supported := range map[string]bool{http.MethodGet: endpoint.Get, http.MethodPut: endpoint.Put, http.MethodPost: endpoint.Post} {
				if !supported {
					continue
				}
				key := method + ":" + suffix
				if number > versions[key] {
					versions[key], resources[key] = number, u.Path
				}
			}
		}
	}
	if len(resources) == 0 {
		return nil, errors.New("机身未公布可用CCAPI资源，请核对启用状态与固件")
	}
	return resources, nil
}

func (r ccapiResources) path(method, operation string) (string, error) {
	path := r[method+":"+operation]
	if path == "" {
		return "", errors.New("当前机身未公布此CCAPI功能")
	}
	return path, nil
}
