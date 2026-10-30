package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"math/big"
	"net/http"
	"strconv"
	"strings"
)

type ccapiSetting struct {
	code      int
	operation string
	name      string
	decode    func(string) (int64, bool)
}

type ccapiSettingState struct {
	setting ccapiSetting
	path    string
	current int64
	choices map[int64]json.RawMessage
	order   []int64
}

var errCCAPISettingValue = errors.New("此机身参数值尚未核对映射")

// Only standard exposure meanings are mapped into the existing common controls.
// Vendor-specific style or focus-area values require their own verified mapping.
var ccapiSettings = []ccapiSetting{
	{0x5007, "shooting/settings/av", "光圈", ccapiAperture},
	{0xD20D, "shooting/settings/tv", "快门速度", ccapiShutter},
	{0x500F, "shooting/settings/iso", "感光度", ccapiISO},
	{0x5010, "shooting/settings/exposurecompensation", "曝光补偿", ccapiExposureCompensation},
	{0x5010, "shooting/settings/exposure", "曝光补偿", ccapiExposureCompensation},
	{0x5005, "shooting/settings/wb", "白平衡", ccapiWhiteBalance},
	{0x500A, "shooting/settings/afoperation", "对焦模式", ccapiFocusMode},
}

func ccapiScalar(value json.RawMessage) (string, bool) {
	var text string
	if json.Unmarshal(value, &text) == nil {
		return text, true
	}
	var number json.Number
	if json.Unmarshal(value, &number) == nil && number.String() != "" {
		return number.String(), true
	}
	return "", false
}

func readCCAPISetting(ctx context.Context, client *ccapiHTTP, resources ccapiResources, setting ccapiSetting) (*ccapiSettingState, error) {
	resource, err := resources.path(http.MethodGet, setting.operation)
	if err != nil {
		return nil, err
	}
	var response struct {
		Value   json.RawMessage
		Ability json.RawMessage
	}
	if err = client.json(ctx, http.MethodGet, resource, nil, &response); err != nil {
		return nil, err
	}
	text, valid := ccapiScalar(response.Value)
	if !valid {
		return nil, errCCAPISettingValue
	}
	current, valid := setting.decode(text)
	if !valid {
		return nil, errCCAPISettingValue
	}
	state := &ccapiSettingState{setting: setting, path: resource, current: current, choices: make(map[int64]json.RawMessage)}
	var ability []json.RawMessage
	// A range/composite ability remains read-only until its semantics are implemented.
	if json.Unmarshal(response.Ability, &ability) != nil {
		return state, nil
	}
	ambiguous := make(map[int64]bool)
	for _, raw := range ability {
		text, valid := ccapiScalar(raw)
		if !valid {
			continue
		}
		value, valid := setting.decode(text)
		if !valid || ambiguous[value] {
			continue
		}
		if previous, exists := state.choices[value]; exists && string(previous) != string(raw) {
			delete(state.choices, value)
			ambiguous[value] = true
			continue
		}
		if _, exists := state.choices[value]; !exists {
			state.order = append(state.order, value)
		}
		state.choices[value] = append(json.RawMessage(nil), raw...)
	}
	return state, nil
}

func setCCAPISetting(ctx context.Context, client *ccapiHTTP, resources ccapiResources, setting ccapiSetting, value int64) error {
	state, err := readCCAPISetting(ctx, client, resources, setting)
	if err != nil {
		return err
	}
	writePath, err := resources.path(http.MethodPut, setting.operation)
	if err != nil {
		return err
	}
	if writePath != state.path {
		return errors.New("此参数读写版本不一致，暂不开放修改")
	}
	raw, exists := state.choices[value]
	if !exists {
		return errors.New("参数值不在相机当前可选范围内")
	}
	if err = client.json(ctx, http.MethodPut, writePath, map[string]json.RawMessage{"value": raw}, nil); err != nil {
		return err
	}
	actual, err := readCCAPISetting(ctx, client, resources, setting)
	if err != nil {
		return errors.New("参数已发送，机身读回未完成，请先核对当前状态")
	}
	if actual.current != value {
		return errors.New("机身尚未确认此参数，请检查拍摄模式")
	}
	return nil
}

func ccapiAperture(raw string) (int64, bool) {
	raw = strings.TrimSpace(strings.ToLower(raw))
	raw = strings.TrimPrefix(strings.TrimPrefix(raw, "f/"), "f")
	value, err := strconv.ParseFloat(raw, 64)
	if err != nil || math.IsNaN(value) || math.IsInf(value, 0) || value <= 0 || value > 128 {
		return 0, false
	}
	return int64(math.Round(value * 100)), true
}

func ccapiShutter(raw string) (int64, bool) {
	raw = strings.TrimSuffix(strings.TrimSpace(strings.ToLower(raw)), "\"")
	if raw == "bulb" {
		return 0, true
	}
	value, valid := new(big.Rat).SetString(raw)
	if !valid || value.Sign() <= 0 || !value.Num().IsInt64() || !value.Denom().IsInt64() {
		return 0, false
	}
	numerator, denominator := value.Num().Int64(), value.Denom().Int64()
	if numerator > 65535 || denominator > 65535 {
		return 0, false
	}
	return numerator<<16 | denominator, true
}

func ccapiISO(raw string) (int64, bool) {
	if strings.EqualFold(raw, "auto") {
		return 0, true
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	return value, err == nil && value > 0 && value <= 10000000
}

func ccapiExposureCompensation(raw string) (int64, bool) {
	raw = strings.TrimSpace(raw)
	var value float64
	if left, right, fraction := strings.Cut(raw, "/"); fraction {
		numerator, first := strconv.ParseFloat(left, 64)
		denominator, second := strconv.ParseFloat(right, 64)
		if first != nil || second != nil || denominator == 0 {
			return 0, false
		}
		value = numerator / denominator
	} else {
		var err error
		value, err = strconv.ParseFloat(raw, 64)
		if err != nil {
			return 0, false
		}
	}
	if math.IsNaN(value) || math.IsInf(value, 0) || value < -10 || value > 10 {
		return 0, false
	}
	return int64(math.Round(value * 1000)), true
}

func ccapiWhiteBalance(raw string) (int64, bool) {
	value, exists := map[string]int64{
		"auto": 2, "daylight": 4, "white_fluorescent": 5, "tungsten": 6, "flash": 7,
		"cloudy": 0x8010, "shade": 0x8011,
	}[strings.ToLower(raw)]
	return value, exists
}

func ccapiFocusMode(raw string) (int64, bool) {
	value := strings.NewReplacer("_", "", "-", "", " ", "").Replace(strings.ToLower(raw))
	mode, exists := map[string]int64{"oneshot": 2, "single": 2, "aiservo": 0x8004, "servo": 0x8004, "aifocus": 0x8005, "manual": 1}[value]
	return mode, exists
}
