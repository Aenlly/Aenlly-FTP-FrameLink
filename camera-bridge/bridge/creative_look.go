package bridge

import (
	"context"
	"errors"
	"slices"
	"time"

	"github.com/knallli/alpha2go/propdb"
	"github.com/knallli/alpha2go/ptpip"
)

// Published PTP candidate, distinct from SDKProp 0x01C5. A live descriptor is
// mandatory; this registry alone is not proof of ILCE-7CM2 hardware support.
const sonyCreativeLook uint16 = 0xD0FA

var creativeLookLabels = map[int64]string{1: "ST", 2: "PT", 3: "NT", 4: "VV", 5: "VV2", 6: "FL", 7: "IN", 8: "SH", 9: "BW", 10: "SE"}

func creativeLookReadable(p ptpip.SonyProp) bool {
	return p.Code == sonyCreativeLook && p.Type >= 1 && p.Type <= 8 && p.Form == 2 && p.Current.Str == "" && len(p.Current.Arr) == 0
}
func creativeLookChoices(p ptpip.SonyProp) []ptpip.SonyValue {
	if !creativeLookReadable(p) {
		return nil
	}
	values := make([]ptpip.SonyValue, 0, len(p.Settable))
	for _, value := range p.Settable {
		if _, known := creativeLookLabels[value.Int]; known && value.Str == "" && len(value.Arr) == 0 {
			values = append(values, value)
		}
	}
	return values
}

func safeCameraProperty(info propdb.Info) bool {
	return safeGroup(info.Group) || info.Code == sonyCreativeLook
}

func creativeDescriptorRow(p ptpip.SonyProp) map[string]any {
	row := map[string]any{"code": p.Code, "dataType": p.Type, "writable": p.Writable, "enabled": p.Enabled, "form": p.Form}
	if p.Type >= 1 && p.Type <= 8 {
		row["current"] = p.Current.Int
		values := make([]int64, 0, len(p.Settable))
		for _, value := range p.Settable {
			values = append(values, value.Int)
		}
		row["settable"] = values
	} else {
		row["valuesRedacted"] = true
	}
	return row
}

// CreativeLookDiagnostics is read-only. It reports the full reply's code list,
// but includes values only for the narrowly scoped modern-look candidate range.
// Wi-Fi strings, access tokens, owner and unrelated properties are never emitted.
func (s *Session) CreativeLookDiagnostics() (string, error) {
	if s.alternate() != nil {
		return "", errors.New("当前连接不是Sony协议，不读取Sony创意外观描述符")
	}
	s.op.Lock()
	defer s.op.Unlock()
	c, parent, err := s.camera()
	if err != nil {
		return "", err
	}
	ctx, stop := context.WithTimeout(parent, 25*time.Second)
	defer stop()
	device, err := c.GetDeviceInfo(ctx)
	if err != nil {
		return "", err
	}
	props, err := c.SonyGetProps(ctx, false)
	if err != nil {
		return "", err
	}
	codes := make([]uint16, 0, len(props))
	rows := []map[string]any{}
	for _, p := range props {
		codes = append(codes, p.Code)
		if p.Code < sonyCreativeLook || p.Code > 0xD103 {
			continue
		}
		rows = append(rows, creativeDescriptorRow(p))
	}
	single := map[string]any{"attempted": false}
	if slices.Contains(codes, sonyCreativeLook) {
		single["attempted"] = true
		actual, readErr := c.SonyGetProp(ctx, sonyCreativeLook)
		if readErr != nil {
			single["error"] = readErr.Error()
		} else if actual.Code != sonyCreativeLook {
			single["codeMismatch"] = true
		} else {
			single["descriptor"] = creativeDescriptorRow(actual)
		}
	}
	return marshal(map[string]any{"model": device.Model, "firmware": device.DeviceVersion, "protocol": "sony-ptpip", "readOnly": true,
		"candidateCode": sonyCreativeLook, "sdkCodeUsed": false, "advertisedByDeviceInfo": slices.Contains(device.DeviceProperties, sonyCreativeLook),
		"replyCodeCount": len(codes), "replyCodes": codes, "candidateDescriptors": rows, "singlePropertyRead": single, "hardwareVerified": false})
}
