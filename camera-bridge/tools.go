//go:build tools

package tools

// Pin the AAR generator and binding runtime in the module graph.
import _ "golang.org/x/mobile/bind"
