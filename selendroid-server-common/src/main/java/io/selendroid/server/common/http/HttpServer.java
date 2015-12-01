/*
 * Copyright 2014 eBay Software Foundation and selendroid committers.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.selendroid.server.common.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.io.IOException;
import java.net.BindException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

public class HttpServer {
  private static final Logger log = Logger.getLogger(HttpServer.class.getName());
  private int port;
  private boolean startOnRandom = false;
  private volatile boolean serverWasStarted = false;
  private Thread serverThread;
  private final List<HttpServlet> handlers = new ArrayList<HttpServlet>();

  public HttpServer(int port) {
    this.port = port;
    this.startOnRandom = false;
  }

  private HttpServer() {
    this.startOnRandom = true;
  }

  public static HttpServer httpServerOnRandomPort() {
    return new HttpServer();
  }

  public void addHandler(HttpServlet handler) {
    handlers.add(handler);
  }

  public void start() {
    if (serverThread != null) {
      throw new IllegalStateException("Server is already running");
    }
    serverThread = new Thread() {
      @Override
      public void run() {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
          ServerBootstrap bootstrap = new ServerBootstrap();
          bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
          bootstrap.group(bossGroup, workerGroup)
                  .channel(NioServerSocketChannel.class)
                  .childHandler(new ServerInitializer(handlers));

          Channel ch;
          if (startOnRandom) {
            ch = startServerOnRandomPort(bootstrap, 20);
          } else {
            ch = bootstrap.bind(port).sync().channel();
          }
          ch.closeFuture().sync();
        } catch (InterruptedException ignored) {
        } finally {
          bossGroup.shutdownGracefully();
          workerGroup.shutdownGracefully();
          TrafficCounter.shutdown();
        }
      }
    };
    serverThread.start();
  }

  private Channel startServerOnRandomPort(ServerBootstrap bootstrap, int numTries) throws InterruptedException {
    int maxPort = 65535;
    int minPort = 1024;
    while (numTries-- > 0) {
      port = new Random().nextInt(maxPort - minPort) + minPort;
      log.info("Trying to bind HttpServer on port " + port);
      try {
        Channel ch = bootstrap.bind(port).sync().channel();
        log.info("Successfully binded HttpServer on port " + port);
        serverWasStarted = true;
        return ch;
      } catch (ChannelException a) {
        log.warning("HttpServer failed to bind on " + port);
        log.info("Number of retries remaining: " + numTries);
      }
    }
    throw new RuntimeException("HttpServer was not able to find a random port");
  }

  public void stop() {
    if (serverThread == null) {
      throw new IllegalStateException("Server is not running");
    }
    serverThread.interrupt();
  }

  public int getPort() {
    return port;
  }

  public void waitForRandomPort() {
    if (startOnRandom == false) {
      throw new IllegalStateException("Server was not started on random port");
    }
    if (serverThread == null) {
      throw new IllegalStateException("Server is not running");
    }
    long endTime = System.currentTimeMillis() + 20000;
    while (!serverWasStarted && System.currentTimeMillis() < endTime) {
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
      }
    }
    if (!serverWasStarted) {
      throw new RuntimeException("Server was unable to start on a random port");
    }
  }

}
