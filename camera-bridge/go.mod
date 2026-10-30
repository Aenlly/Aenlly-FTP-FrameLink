module framelink.local/camera-bridge

go 1.26.0

require github.com/knallli/alpha2go v0.0.0

require (
	golang.org/x/crypto v0.58.0
	golang.org/x/mobile v0.0.0-20261011034528-197770444867
	golang.org/x/sys v0.49.0
)

require (
	golang.org/x/mod v0.42.0 // indirect
	golang.org/x/sync v0.24.0 // indirect
	golang.org/x/tools v0.52.0 // indirect
)

replace github.com/knallli/alpha2go => ../third_party/alpha2go
