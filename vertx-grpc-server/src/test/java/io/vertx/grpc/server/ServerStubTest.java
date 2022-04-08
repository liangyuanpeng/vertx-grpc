/*
 * Copyright (c) 2011-2022 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.grpc.server;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.examples.streaming.Empty;
import io.grpc.examples.streaming.Item;
import io.grpc.examples.streaming.StreamingGrpc;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.grpc.server.stub.GrpcStub;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class ServerStubTest extends ServerTestBase {

  @Override
  protected void testUnary(TestContext should, String requestEncoding, String responseEncoding) {
    GreeterGrpc.GreeterImplBase impl = new GreeterGrpc.GreeterImplBase() {
      @Override
      public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        if (!responseEncoding.equals("identity")) {
          ((ServerCallStreamObserver<?>)responseObserver).setCompression("gzip");
        }
        if (!requestEncoding.equals("identity")) {
          // No way to check the request encoding with the API
        }
        responseObserver.onNext(HelloReply.newBuilder().setMessage("Hello " + request.getName()).build());
        responseObserver.onCompleted();
      }
    };

    GrpcServer server = GrpcServer.server();
    GrpcStub serverStub = GrpcStub.stub(impl);
    serverStub.bind(server);
    startServer(server);

    super.testUnary(should, requestEncoding, responseEncoding);
  }

  @Override
  public void testStatus(TestContext should) {

    GreeterGrpc.GreeterImplBase impl = new GreeterGrpc.GreeterImplBase() {
      @Override
      public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE));
      }
    };

    GrpcServer server = GrpcServer.server();
    GrpcStub serverStub = GrpcStub.stub(impl);
    serverStub.bind(server);
    startServer(server);

    super.testStatus(should);
  }

  @Override
  public void testServerStreaming(TestContext should) {

    StreamingGrpc.StreamingImplBase impl = new StreamingGrpc.StreamingImplBase() {
      @Override
      public void source(Empty request, StreamObserver<Item> responseObserver) {
        for (int i = 0; i < NUM_ITEMS; i++) {
          Item item = Item.newBuilder().setValue("the-value-" + i).build();
          responseObserver.onNext(item);
        }
        responseObserver.onCompleted();
      }
    };

    GrpcServer server = GrpcServer.server();
    GrpcStub serverStub = GrpcStub.stub(impl);
    serverStub.bind(server);
    startServer(server);

    super.testServerStreaming(should);
  }

  @Override
  public void testClientStreaming(TestContext should) throws Exception {

    StreamingGrpc.StreamingImplBase impl = new StreamingGrpc.StreamingImplBase() {
      @Override
      public StreamObserver<Item> sink(StreamObserver<Empty> responseObserver) {
        return new StreamObserver<Item>() {
          int seq = 0;
          @Override
          public void onNext(Item value) {
            should.assertEquals(value.getValue(), "the-value-" + seq++);
          }
          @Override
          public void onError(Throwable t) {

          }
          @Override
          public void onCompleted() {
            should.assertEquals(NUM_ITEMS, seq);
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
          }
        };
      }
    };

    GrpcServer server = GrpcServer.server();
    GrpcStub serverStub = GrpcStub.stub(impl);
    serverStub.bind(server);
    startServer(server);

    super.testClientStreaming(should);
  }

  @Override
  public void testBidiStreaming(TestContext should) throws Exception {

    StreamingGrpc.StreamingImplBase impl = new StreamingGrpc.StreamingImplBase() {
      @Override
      public StreamObserver<Item> pipe(StreamObserver<Item> responseObserver) {
        return new StreamObserver<Item>() {
          @Override
          public void onNext(Item value) {
            responseObserver.onNext(value);
          }
          @Override
          public void onError(Throwable t) {

          }
          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };

    GrpcServer server = GrpcServer.server();
    GrpcStub serverStub = GrpcStub.stub(impl);
    serverStub.bind(server);
    startServer(server);

    super.testBidiStreaming(should);
  }

  @Override
  public void testMetadata(TestContext should) {

    GreeterGrpc.GreeterImplBase impl = new GreeterGrpc.GreeterImplBase() {
      @Override
      public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        responseObserver.onNext(HelloReply.newBuilder().setMessage("Hello " + request.getName()).build());
        responseObserver.onCompleted();
      }
    };

    ServerInterceptor interceptor = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        should.assertEquals("custom_request_header_value", headers.get(Metadata.Key.of("custom_request_header", Metadata.ASCII_STRING_MARSHALLER)));
        should.assertEquals(0, testMetadataStep.getAndIncrement());
        return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
          @Override
          public void sendHeaders(Metadata headers) {
            headers.put(Metadata.Key.of("custom_response_header", io.grpc.Metadata.ASCII_STRING_MARSHALLER), "custom_response_header_value");
            should.assertEquals(1, testMetadataStep.getAndIncrement());
            super.sendHeaders(headers);
          }
          @Override
          public void close(Status status, Metadata trailers) {
            trailers.put(Metadata.Key.of("custom_response_trailer", io.grpc.Metadata.ASCII_STRING_MARSHALLER), "custom_response_trailer_value");
            should.assertEquals(2, testMetadataStep.getAndIncrement());
            super.close(status, trailers);
          }
        },headers);
      }
    };

    GrpcServer server = GrpcServer.server();
    GrpcStub serverStub = GrpcStub.stub(ServerInterceptors.intercept(impl, interceptor));
    serverStub.bind(server);
    startServer(server);

    super.testMetadata(should);
  }

  @Test
  public void testHandleReset(TestContext should) {

    Async test = should.async();
    StreamingGrpc.StreamingImplBase impl = new StreamingGrpc.StreamingImplBase() {
      @Override
      public StreamObserver<Item> pipe(StreamObserver<Item> responseObserver) {
        return new StreamObserver<Item>() {
          @Override
          public void onNext(Item value) {
            responseObserver.onNext(value);
          }
          @Override
          public void onError(Throwable t) {
            should.assertEquals(t.getClass(), StatusRuntimeException.class);
            should.assertEquals(Status.Code.CANCELLED, ((StatusRuntimeException)t).getStatus().getCode());
            test.complete();
          }
          @Override
          public void onCompleted() {
          }
        };
      }
    };

    GrpcServer server = GrpcServer.server();
    GrpcStub serverStub = GrpcStub.stub(impl);
    serverStub.bind(server);
    startServer(server);

    super.testHandleReset(should);
  }
}
