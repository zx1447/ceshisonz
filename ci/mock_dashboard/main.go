// CI-only fake dashboard: it accepts a hard-coded dummy credential on loopback.
package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"sync"

	pb "github.com/nezhahq/agent/proto"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

const dummySecret = "ci-dummy-not-a-real-secret"
const dummyUUID = "123e4567-e89b-12d3-a456-426614174000"

type fakeDashboard struct {
	pb.UnimplementedNezhaServiceServer
	hostOnce  sync.Once
	stateOnce sync.Once
}

func (f *fakeDashboard) ReportSystemInfo2(ctx context.Context, _ *pb.Host) (*pb.Uint64Receipt, error) {
	md, ok := metadata.FromIncomingContext(ctx)
	if !ok || len(md.Get("client-secret")) != 1 || md.Get("client-secret")[0] != dummySecret ||
		len(md.Get("client-uuid")) != 1 || md.Get("client-uuid")[0] != dummyUUID {
		return nil, status.Error(codes.Unauthenticated, "dummy credentials did not match")
	}
	f.hostOnce.Do(func() { fmt.Println("HOST_OK") })
	return &pb.Uint64Receipt{Data: 1}, nil
}

func (f *fakeDashboard) RequestTask(stream pb.NezhaService_RequestTaskServer) error {
	<-stream.Context().Done()
	return nil
}

func (f *fakeDashboard) ReportSystemState(stream pb.NezhaService_ReportSystemStateServer) error {
	for {
		if _, err := stream.Recv(); err != nil {
			return nil
		}
		f.stateOnce.Do(func() { fmt.Println("STATE_OK") })
		if err := stream.Send(&pb.Receipt{Proced: true}); err != nil {
			return nil
		}
	}
}

func (f *fakeDashboard) ReportGeoIP(_ context.Context, _ *pb.GeoIP) (*pb.GeoIP, error) {
	return &pb.GeoIP{}, nil
}

func main() {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		fmt.Fprintln(os.Stderr, "mock dashboard listen failed")
		os.Exit(1)
	}
	server := grpc.NewServer()
	pb.RegisterNezhaServiceServer(server, &fakeDashboard{})
	fmt.Println("READY " + listener.Addr().String())
	if err := server.Serve(listener); err != nil {
		fmt.Fprintln(os.Stderr, "mock dashboard stopped")
	}
}
