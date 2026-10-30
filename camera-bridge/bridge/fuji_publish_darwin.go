package bridge

import "golang.org/x/sys/unix"

func fujiPublishOriginal(source, target string) error {
	return unix.RenamexNp(source, target, unix.RENAME_EXCL)
}
