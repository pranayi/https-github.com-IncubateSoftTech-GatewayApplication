package com.incubatesoft.nats;


import java.nio.charset.StandardCharsets;
import java.time.Duration;
import io.nats.client.Connection;
import io.nats.client.Nats;

public class NatsPublisher {

	public NatsPublisher() {
		
	}
	
	/**
	 * the publishMessage() publishes data packet received from a device to the Nats Server
	 * @param deviceId, deviceData
	 * @author Aditya
	 */
	public void publishMessage(String deviceId, StringBuilder deviceData) {
		try {
			Connection natConn = Nats.connect("nats://0.0.0.0:4222");
            // [begin publish_bytes]
			System.out.println(" publishMessage() called !");
			System.out.println(" Nats Connection : "+natConn);			
            
            // Publish a message which includes both deviceId and deviceData
            String msgToPublish = deviceId + "||" +deviceData.toString();

            natConn.publish("data_packet", msgToPublish.toString().getBytes(StandardCharsets.UTF_8));

            // Make sure the message goes through before we close
            natConn.flush(Duration.ZERO);   
            natConn.close();
            // [end publish_bytes]
        } catch (Exception e) {
            e.printStackTrace();
        }

	}
}
