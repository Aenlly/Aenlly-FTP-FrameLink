//go:build linux || android

package bridge

import "golang.org/x/sys/unix"

// Android SELinux may forbid hard links even inside app-private storage.
// RENAME_NOREPLACE atomically publishes a complete file without replacing any target.
func fujiPublishOriginal(source, target string) error {
	return unix.Renameat2(unix.AT_FDCWD, source, unix.AT_FDCWD, target, unix.RENAME_NOREPLACE)
}
