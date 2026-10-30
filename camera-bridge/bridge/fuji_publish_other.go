//go:build !linux && !android && !darwin

package bridge

import "errors"

func fujiPublishOriginal(source, target string) error {
	return errors.New("当前平台尚未实现AutoSave原片的原子防覆盖发布")
}
