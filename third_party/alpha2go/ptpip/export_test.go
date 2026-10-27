package ptpip

import "time"

// SetSonyRestorePace shortens the settings-restore pauses; it returns the undo.
func SetSonyRestorePace(d time.Duration) func() {
	old := sonyRestorePace
	sonyRestorePace = d
	return func() { sonyRestorePace = old }
}
