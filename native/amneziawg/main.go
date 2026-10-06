package main

import (
	"context"
	"flag"
	"fmt"
	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"net"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	path := flag.String("config", "", "AmneziaWG configuration")
	listen := flag.String("listen", "127.0.0.1:18001", "local SOCKS listener")
	check := flag.Bool("check", false, "validate without starting")
	flag.Parse()
	if err := run(*path, *listen, *check); err != nil {
		fmt.Fprintln(os.Stderr, "KamNG AmneziaWG:", err)
		os.Exit(1)
	}
}
func run(path, listen string, check bool) error {
	raw, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("cannot read configuration")
	}
	cfg, err := parseConfig(string(raw))
	if err != nil {
		return err
	}
	if check {
		return nil
	}
	addr, err := net.ResolveTCPAddr("tcp", listen)
	if err != nil || !addr.IP.IsLoopback() {
		return fmt.Errorf("listener must be loopback")
	}
	tun, network, err := netstack.CreateNetTUN(cfg.addresses, cfg.dns, cfg.mtu)
	if err != nil {
		return fmt.Errorf("cannot create userspace tunnel")
	}
	// Android's existing VPN and Xray retain all routing ownership.
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer dev.Close()
	if err = dev.IpcSet(cfg.ipc); err != nil {
		return fmt.Errorf("engine rejected tunnel settings")
	}
	if err = dev.Up(); err != nil {
		return fmt.Errorf("cannot activate tunnel")
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	return serveSOCKS(ctx, addr, network.DialContext)
}
