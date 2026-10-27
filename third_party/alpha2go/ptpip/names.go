package ptpip

import "fmt"

var opNames = map[uint16]string{
	0x1001: "GetDeviceInfo", 0x1002: "OpenSession", 0x1003: "CloseSession", 0x1004: "GetStorageIDs",
	0x1005: "GetStorageInfo", 0x1006: "GetNumObjects", 0x1007: "GetObjectHandles", 0x1008: "GetObjectInfo",
	0x1009: "GetObject", 0x100A: "GetThumb", 0x100B: "DeleteObject", 0x100C: "SendObjectInfo",
	0x100D: "SendObject", 0x100E: "InitiateCapture", 0x100F: "FormatStore", 0x1010: "ResetDevice",
	0x1014: "GetDevicePropDesc", 0x1015: "GetDevicePropValue", 0x1016: "SetDevicePropValue",
	0x101B: "GetPartialObject", 0x101C: "InitiateOpenCapture",
	0x9801: "MTP GetObjectPropsSupported", 0x9802: "MTP GetObjectPropDesc",
	0x9803: "MTP GetObjectPropValue", 0x9805: "MTP GetObjPropList",
	// Sony vendor operations.
	0x9201: "Sony SDIO_Connect", 0x9202: "Sony SDIO_GetExtDeviceInfo", 0x9203: "Sony GetDevicePropdesc",
	0x9204: "Sony GetDevicePropertyValue", 0x9205: "Sony SDIO_SetExtDevicePropValue",
	0x9206: "Sony GetControlDeviceDesc", 0x9207: "Sony SDIO_ControlDevice",
	0x9209: "Sony SDIO_GetAllExtDevicePropInfo", 0x9210: "Sony SDIO_OpenSession",
	0x9211: "Sony SDIO_GetPartialLargeObject", 0x9212: "Sony SDIO_SetContentsTransferMode",
	0x9215: "Sony SDIO_GetDisplayStringList", 0x9223: "Sony SDIO_GetLensInformation",
	0x922F: "Sony SDIO_OperationsResultsSupported",
	0x920E: "Sony GetFTPServerNames", 0x923A: "Sony GetDeviceDescription",
	0x921A: "Sony UploadData", 0x921B: "Sony ControlUploadData", 0x9238: "Sony GetOSDImage",
	0x9249: "Sony SetTimeZone", 0x9250: "Sony DeleteContent",
	0x9248: "Sony GetTimeZone", 0x9251: "Sony GetExtDeviceProp",
	0x923B: "Sony GetCapturedDateList", 0x923C: "Sony GetContentsInfoList",
	0x923D: "Sony GetContentsData", 0x923E: "Sony GetContentsCompressedData",
}

var respNames = map[uint16]string{
	0x2001: "OK", 0x2002: "GeneralError", 0x2003: "SessionNotOpen", 0x2004: "InvalidTransactionID",
	0x2005: "OperationNotSupported", 0x2006: "ParameterNotSupported", 0x2007: "IncompleteTransfer",
	0x2008: "InvalidStorageID", 0x2009: "InvalidObjectHandle", 0x200A: "DevicePropNotSupported",
	0x200B: "InvalidObjectFormatCode", 0x200C: "StoreFull", 0x200D: "ObjectWriteProtected",
	0x200E: "StoreReadOnly", 0x200F: "AccessDenied", 0x2010: "NoThumbnailPresent",
	0x2013: "StoreNotAvailable", 0x2014: "SpecificationByFormatUnsupported",
	0x2019: "DeviceBusy", 0x201A: "InvalidParentObject", 0x201D: "InvalidParameter",
	0x201E: "SessionAlreadyOpen", 0x201F: "TransactionCancelled",
}

var eventNames = map[uint16]string{
	0x4001: "CancelTransaction", 0x4002: "ObjectAdded", 0x4003: "ObjectRemoved", 0x4004: "StoreAdded",
	0x4005: "StoreRemoved", 0x4006: "DevicePropChanged", 0x4007: "ObjectInfoChanged",
	0x4008: "DeviceInfoChanged", 0x400A: "StoreFull", 0x400C: "StorageInfoChanged", 0x400D: "CaptureComplete",
	0xC201: "Sony ObjectAdded", 0xC202: "Sony ObjectRemoved", 0xC203: "Sony DevicePropChanged",
	0xC206: "Sony CapturedEvent", 0xC209: "Sony SettingsRestoreResult", 0xC20D: "Sony ContentsTransferEvent",
	0xC214: "Sony UploadResult", 0xC228: "Sony CautionDisplay", 0xC240: "Sony ContentsChanged",
}

func name(m map[uint16]string, v uint16) string {
	if n, ok := m[v]; ok {
		return fmt.Sprintf("%s (0x%04X)", n, v)
	}
	return fmt.Sprintf("0x%04X", v)
}

// OpName describes an operation code.
func OpName(v uint16) string { return name(opNames, v) }

// RespName describes a response code.
func RespName(v uint16) string { return name(respNames, v) }

// EventName describes an event code.
func EventName(v uint16) string { return name(eventNames, v) }
