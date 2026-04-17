package server;

import java.net.InetAddress;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single stable {@code server.instance-id} for presence + broadcast queue binding when property is unset.
 */
@Configuration
public class ServerIdentityConfiguration {

    public static final String SERVER_INSTANCE_ID_BEAN = "serverInstanceIdentity";

    @Bean(SERVER_INSTANCE_ID_BEAN)
    public String serverInstanceIdentity(@Value("${server.instance-id:}") String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            return "srv-" + UUID.randomUUID();
        }
    }
}
