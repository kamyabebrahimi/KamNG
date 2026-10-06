package main

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

type dialFunc func(context.Context, string, string) (net.Conn, error)

func serveSOCKS(ctx context.Context, addr *net.TCPAddr, dial dialFunc) error {
	listener, err := net.ListenTCP("tcp", addr)
	if err != nil {
		return errors.New("local listener unavailable")
	}
	defer listener.Close()
	go func() { <-ctx.Done(); listener.Close() }()
	slots := make(chan struct{}, 128)
	var workers sync.WaitGroup
	defer workers.Wait()
	for {
		c, e := listener.AcceptTCP()
		if e != nil {
			if ctx.Err() != nil {
				return nil
			}
			return errors.New("listener stopped")
		}
		select {
		case slots <- struct{}{}:
			workers.Add(1)
			go func() { defer workers.Done(); defer func() { <-slots }(); defer c.Close(); handleSOCKS(ctx, c, dial) }()
		default:
			c.Close()
		}
	}
}
func handleSOCKS(parent context.Context, c net.Conn, dial dialFunc) {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	go func() { <-ctx.Done(); c.Close() }()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	var head [2]byte
	if _, e := io.ReadFull(c, head[:]); e != nil || head[0] != 5 || head[1] == 0 {
		return
	}
	methods := make([]byte, int(head[1]))
	if _, e := io.ReadFull(c, methods); e != nil {
		return
	}
	noAuth := false
	for _, m := range methods {
		if m == 0 {
			noAuth = true
		}
	}
	if !noAuth {
		c.Write([]byte{5, 255})
		return
	}
	if _, e := c.Write([]byte{5, 0}); e != nil {
		return
	}
	var req [3]byte
	if _, e := io.ReadFull(c, req[:]); e != nil || req[0] != 5 || req[2] != 0 {
		return
	}
	target, e := readTarget(c)
	if e != nil {
		reply(c, 8, nil)
		return
	}
	switch req[1] {
	case 1:
		dctx, dcancel := context.WithTimeout(ctx, 20*time.Second)
		remote, e := dial(dctx, "tcp", target)
		dcancel()
		if e != nil {
			reply(c, 5, nil)
			return
		}
		defer remote.Close()
		go func() { <-ctx.Done(); remote.Close() }()
		if reply(c, 0, nil) != nil {
			return
		}
		c.SetDeadline(time.Time{})
		done := make(chan struct{}, 1)
		go func() {
			io.Copy(remote, c)
			if half, ok := remote.(interface{ CloseWrite() error }); ok {
				half.CloseWrite()
			} else {
				remote.Close()
			}
			done <- struct{}{}
		}()
		io.Copy(c, remote)
		remote.Close()
		c.Close()
		<-done
	case 3:
		udpAssociate(ctx, c, dial)
	default:
		reply(c, 7, nil)
	}
}
func readTarget(r io.Reader) (string, error) {
	var kind [1]byte
	if _, e := io.ReadFull(r, kind[:]); e != nil {
		return "", e
	}
	var host string
	switch kind[0] {
	case 1:
		b := make([]byte, 4)
		if _, e := io.ReadFull(r, b); e != nil {
			return "", e
		}
		host = net.IP(b).String()
	case 4:
		b := make([]byte, 16)
		if _, e := io.ReadFull(r, b); e != nil {
			return "", e
		}
		host = net.IP(b).String()
	case 3:
		var size [1]byte
		if _, e := io.ReadFull(r, size[:]); e != nil || size[0] == 0 {
			return "", errors.New("invalid domain")
		}
		b := make([]byte, int(size[0]))
		if _, e := io.ReadFull(r, b); e != nil {
			return "", e
		}
		host = string(b)
	default:
		return "", errors.New("unsupported address")
	}
	var port [2]byte
	if _, e := io.ReadFull(r, port[:]); e != nil {
		return "", e
	}
	return net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(port[:])))), nil
}
func reply(c net.Conn, status byte, addr *net.UDPAddr) error {
	ip := net.IPv4zero
	port := 0
	if addr != nil {
		ip = addr.IP.To4()
		port = addr.Port
	}
	b := []byte{5, status, 0, 1, 0, 0, 0, 0, 0, 0}
	copy(b[4:8], ip)
	binary.BigEndian.PutUint16(b[8:], uint16(port))
	_, e := c.Write(b)
	return e
}
func udpAssociate(parent context.Context, control net.Conn, dial dialFunc) {
	relay, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		reply(control, 1, nil)
		return
	}
	defer relay.Close()
	if reply(control, 0, relay.LocalAddr().(*net.UDPAddr)) != nil {
		return
	}
	control.SetDeadline(time.Time{})
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	go func() { io.Copy(io.Discard, control); cancel(); relay.Close() }()
	var mu sync.Mutex
	remotes := map[string]net.Conn{}
	defer func() {
		mu.Lock()
		defer mu.Unlock()
		for _, remote := range remotes {
			remote.Close()
		}
	}()
	var client *net.UDPAddr
	buf := make([]byte, 65535)
	for {
		relay.SetReadDeadline(time.Now().Add(120 * time.Second))
		n, src, e := relay.ReadFromUDP(buf)
		if e != nil {
			return
		}
		if !src.IP.IsLoopback() {
			continue
		}
		if client == nil {
			client = src
		}
		if !client.IP.Equal(src.IP) || client.Port != src.Port || n < 4 || buf[0] != 0 || buf[1] != 0 || buf[2] != 0 {
			continue
		}
		reader := &byteReader{data: buf[3:n]}
		target, e := readTarget(reader)
		if e != nil {
			continue
		}
		offset := 3 + reader.position
		if offset >= n {
			continue
		}
		mu.Lock()
		remote := remotes[target]
		full := len(remotes) >= 64
		mu.Unlock()
		if remote == nil {
			if full {
				continue
			}
			dctx, dcancel := context.WithTimeout(ctx, 10*time.Second)
			remote, e = dial(dctx, "udp", target)
			dcancel()
			if e != nil {
				continue
			}
			mu.Lock()
			remotes[target] = remote
			mu.Unlock()
			// Echo the requested address, including a domain address, in each reply.
			header := append([]byte(nil), buf[:offset]...)
			destination := *client
			go func(r net.Conn, key string, prefix []byte) {
				defer r.Close()
				defer func() { mu.Lock(); delete(remotes, key); mu.Unlock() }()
				data := make([]byte, 65535)
				for {
					r.SetReadDeadline(time.Now().Add(120 * time.Second))
					count, e := r.Read(data)
					if e != nil {
						return
					}
					packet := append(append([]byte(nil), prefix...), data[:count]...)
					if _, e = relay.WriteToUDP(packet, &destination); e != nil {
						return
					}
				}
			}(remote, target, header)
		}
		remote.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if _, e = remote.Write(buf[offset:n]); e != nil {
			remote.Close()
		}
	}
}

type byteReader struct {
	data     []byte
	position int
}

func (r *byteReader) Read(p []byte) (int, error) {
	if r.position >= len(r.data) {
		return 0, io.EOF
	}
	n := copy(p, r.data[r.position:])
	r.position += n
	return n, nil
}
