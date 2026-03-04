package server;

import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/**
 * Netty configuration: increase worker threads to handle high concurrent WebSocket connections
 * 
 * By default, Reactor Netty uses CPU core count worker threads.
 * For high concurrent I/O-intensive tasks (like WebSocket), more threads are needed.
 * 
 * CPU usage is 60% but throughput is limited, usually because:
 * 1. Not enough threads, causing messages to wait in queues
 * 2. Network I/O latency (round-trip time RTT)
 * 3. Backpressure causing message backlog
 */
@Configuration
public class NettyConfig {

    /**
     * Customize Netty worker threads
     * Adjust based on your EC2 instance configuration (e.g. 4-core CPU -> 16-32 threads)
     */
    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> nettyCustomizer() {
        return factory -> {
            // Set worker threads: recommended to be 4-8 times the CPU core count
            // e.g. 4-core -> 16-32 threads, 8-core -> 32-64 threads
            // Can be overridden via system property: -Dnetty.worker.threads=32
            int workerThreads = Integer.getInteger("netty.worker.threads", 
                Math.max(16, Runtime.getRuntime().availableProcessors() * 4));
            
            factory.addServerCustomizers(httpServer -> {
                EventLoopGroup eventLoopGroup = new NioEventLoopGroup(workerThreads);
                return httpServer.runOn(eventLoopGroup);
            });
        };
    }
}
