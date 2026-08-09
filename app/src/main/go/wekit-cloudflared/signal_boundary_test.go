package main

import (
	"go/ast"
	"go/parser"
	"go/token"
	"path/filepath"
	"strconv"
	"testing"
)

func TestEmbeddedBridgeDoesNotInstallProcessSignalHandlers(t *testing.T) {
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	for _, path := range files {
		if filepath.Ext(path) != ".go" || filepath.Base(path) == "signal_boundary_test.go" {
			continue
		}
		parsed, err := parser.ParseFile(token.NewFileSet(), path, nil, 0)
		if err != nil {
			t.Fatalf("parse %s: %v", path, err)
		}
		for _, imported := range parsed.Imports {
			name, err := strconv.Unquote(imported.Path.Value)
			if err != nil {
				t.Fatalf("unquote import in %s: %v", path, err)
			}
			if name == "os/signal" {
				t.Fatalf("%s imports os/signal; embedded bridge must not install process handlers", path)
			}
			if name == "github.com/cloudflare/cloudflared/signal" &&
				filepath.Base(path) != "connected_signal.go" {
				t.Fatalf("%s bypasses the reviewed safe one-shot adapter", path)
			}
		}
		ast.Inspect(parsed, func(node ast.Node) bool {
			call, ok := node.(*ast.CallExpr)
			if !ok {
				return true
			}
			selector, ok := call.Fun.(*ast.SelectorExpr)
			if ok && (selector.Sel.Name == "Notify" || selector.Sel.Name == "NotifyContext") {
				t.Fatalf("%s registers or invokes a signal notification handler", path)
			}
			return true
		})
	}
}
