package main

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

func handshake(t *testing.T, c net.Conn, command byte, target []byte) []byte {
	t.Helper()
	c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, e := c.Write([]byte{5, 1, 0}); e != nil {
		t.Fatal(e)
	}
	auth := make([]byte, 2)
	if _, e := io.ReadFull(c, auth); e != nil || auth[0] != 5 || auth[1] != 0 {
		t.Fatalf("auth %v %v", auth, e)
	}
	req := append([]byte{5, command, 0}, target...)
	if _, e := c.Write(req); e != nil {
		t.Fatal(e)
	}
	response := make([]byte, 10)
	if _, e := io.ReadFull(c, response); e != nil || response[1] != 0 {
		t.Fatalf("reply %v %v", response, e)
	}
	return response
}
func ipv4Target(port int) []byte {
	out := []byte{1, 127, 0, 0, 1, 0, 0}
	binary.BigEndian.PutUint16(out[5:], uint16(port))
	return out
}
func TestSOCKSTCPRelayAndCancellation(t *testing.T) {
	echo, e := net.ListenTCP("tcp", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	defer echo.Close()
	go func() {
		c, e := echo.Accept()
		if e == nil {
			defer c.Close()
			io.Copy(c, c)
		}
	}()
	client, server := net.Pipe()
	defer client.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { defer close(done); defer server.Close(); handleSOCKS(ctx, server, (&net.Dialer{}).DialContext) }()
	handshake(t, client, 1, ipv4Target(echo.Addr().(*net.TCPAddr).Port))
	client.Write([]byte("through SOCKS"))
	got := make([]byte, 13)
	if _, e = io.ReadFull(client, got); e != nil || string(got) != "through SOCKS" {
		t.Fatalf("relay %q %v", got, e)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("TCP worker survived cancellation")
	}
}
func TestSOCKSUDPRelayAndCancellation(t *testing.T) {
	echo, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	defer echo.Close()
	go func() {
		b := make([]byte, 1024)
		n, a, e := echo.ReadFromUDP(b)
		if e == nil {
			echo.WriteToUDP(b[:n], a)
		}
	}()
	client, server := net.Pipe()
	defer client.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { defer close(done); defer server.Close(); handleSOCKS(ctx, server, (&net.Dialer{}).DialContext) }()
	response := handshake(t, client, 3, ipv4Target(0))
	relay := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(binary.BigEndian.Uint16(response[8:]))}
	socket, e := net.DialUDP("udp4", nil, relay)
	if e != nil {
		t.Fatal(e)
	}
	defer socket.Close()
	socket.SetDeadline(time.Now().Add(3 * time.Second))
	packet := append([]byte{0, 0, 0}, ipv4Target(echo.LocalAddr().(*net.UDPAddr).Port)...)
	packet = append(packet, []byte("udp payload")...)
	if _, e = socket.Write(packet); e != nil {
		t.Fatal(e)
	}
	b := make([]byte, 1024)
	n, e := socket.Read(b)
	if e != nil || string(b[10:n]) != "udp payload" {
		t.Fatalf("UDP relay %v", e)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("UDP worker survived cancellation")
	}
}
