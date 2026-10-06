package main

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func TestSOCKSAddressParsing(t *testing.T) {
	for _, sample := range []struct {
		bytes []byte
		want  string
	}{{[]byte{1, 1, 2, 3, 4, 0, 80}, "1.2.3.4:80"}, {[]byte{3, 3, 'a', 'b', 'c', 1, 187}, "abc:443"}} {
		got, e := readTarget(bytes.NewReader(sample.bytes))
		if e != nil || got != sample.want {
			t.Fatalf("%s %v", got, e)
		}
	}
	v6 := append([]byte{4}, make([]byte, 18)...)
	v6[16] = 1
	binary.BigEndian.PutUint16(v6[17:], 53)
	if got, e := readTarget(bytes.NewReader(v6)); e != nil || got != "[::1]:53" {
		t.Fatalf("%s %v", got, e)
	}
}
func TestRejectsTruncatedAndUnknownAddress(t *testing.T) {
	for _, b := range [][]byte{{}, {1, 1}, {3, 0}, {9}} {
		if _, e := readTarget(bytes.NewReader(b)); e == nil {
			t.Fatal("accepted invalid SOCKS address")
		}
	}
}
