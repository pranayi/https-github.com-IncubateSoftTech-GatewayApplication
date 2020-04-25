package com.incubatesoft.gateway;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.incubatesoft.nats.NatsPublisher;

public class DeviceConnectivity implements Runnable{
	private ServerSocketChannel serverChannel;
	private Selector selector;

	// hardcoded values to be moved out to a property or .env file
	public static final String ADDRESS = "0.0.0.0"; //-BMC
	public static final int PORT = 12324; //12324 - BMC , 12323 - JMC 

	private SimpleDateFormat ftDateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	private static ConcurrentHashMap<SocketChannel, Long>  mClientStatus = new ConcurrentHashMap<SocketChannel, Long>();
	public int clients = 0;
	private Map<SocketChannel, List<?>> dataTracking = new HashMap<SocketChannel, List<?>>();
	private ByteBuffer readBuffer = ByteBuffer.allocate(16384); //16*1024 = 16384 [16KB]
	private Map<Channel, String> DeviceState = new HashMap<Channel, String>(); 
	private Map<String,Long> DeviceLastTimeStamp = new HashMap<String, Long>();
	private NatsPublisher natsPublisher = new NatsPublisher();
	
	public DeviceConnectivity() {
		init();
	}

	private void init() {
		try {
			selector = Selector.open();
			serverChannel = ServerSocketChannel.open();
			serverChannel.configureBlocking(false);

			serverChannel.socket().bind(new java.net.InetSocketAddress(ADDRESS, PORT),5000);
			serverChannel.register(selector, 16);

		}catch(IOException ioexp) {
			ioexp.printStackTrace();
		}catch(Exception exp) {
			exp.printStackTrace();
		}		
	}

	public void run(){
		Date recDateTime = new Date();
		System.out.println("Now accepting connections..." + ftDateTime.format(recDateTime));
		byte[] data;
		while (!Thread.currentThread().isInterrupted()) {
			try {
				System.out.println(" selector : "+selector);
				int ready = selector.select();
				if (ready != 0){
					Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
					while (keys.hasNext()) {
						System.out.println(" inside while.. ");
						
						SelectionKey key = (SelectionKey)keys.next();
						keys.remove();
						if (key.isValid()){
							System.out.println(" key is valid.. ");
							
							if (key.isAcceptable()){
								System.out.println(" key.isAcceptable() : "+key.isAcceptable());
								accept(key);
							}
							if (key.isReadable()){
								// broke down the tasks here - Sarat
								System.out.println(" key.isReadable() : "+key.isReadable());
								data = read(key);
								processDeviceStream(key, data);
							}
						}
					}
				}
			}
			catch (Exception rExp) {                
				rExp.printStackTrace(); 
			}
		}        	
	}

	private void accept(SelectionKey key) throws IOException {
		ServerSocketChannel serverSocketChannel = (ServerSocketChannel)key.channel();
		SocketChannel socketChannel = serverSocketChannel.accept();
		mClientStatus.put(socketChannel, System.currentTimeMillis());
		if (socketChannel == null)
		{
			throw new IOException("Socket Channel is null");
		}
		socketChannel.configureBlocking(false);
		clients++;
		socketChannel.register(selector, 1);
		dataTracking.put(socketChannel, new ArrayList<Object>());
	}

	/**
	 * Modified the Read function
	 * 
	 * @param key
	 * @return
	 * @throws IOException
	 * @author sarat
	 */
	private byte[] read(SelectionKey key) throws IOException {
		SocketChannel channel = (SocketChannel)key.channel();
		readBuffer.clear();
		int length = channel.read(readBuffer);
		readBuffer.flip();
		byte[] data = new byte[length];
		readBuffer.get(data, 0, length);
		if (length == -1) {
			mClientStatus.remove(channel);
			clients--;
			channel.close();
			key.cancel();
			throw new IOException("No data found");
		}else {
			return data;
		}
	}

	/**
	 * Quick sampling of the data received
	 * @param data
	 * @return
	 * @author sarat
	 */
	private StringBuilder byteToStringBuilder(byte[] data) {
		StringBuilder sbLog = new StringBuilder();
		for (byte b : data) {
			sbLog.append(String.format("%02x", new Object[] { b }));
		}
		//System.out.println(" data packet : "+ sbLog);
		return sbLog;
	}

	/**
	 * This method sends the initial handshake response
	 * @param key
	 * @param flag
	 * @throws IOException
	 * @author sarat
	 */
	private void sendDeviceHandshake(SelectionKey key, boolean flag) throws IOException {
		SocketChannel channel = (SocketChannel)key.channel();
		byte[] ackData = new byte[1];
		if(flag) {
			ackData[0]=01;	
		}else {
			ackData[0]=00;
		}


		ByteBuffer bSend = ByteBuffer.allocate(ackData.length);
		bSend.clear();
		bSend.put(ackData);
		bSend.flip();
		while (bSend.hasRemaining()) {
			try {
				channel.write(bSend);
			} catch (IOException e) {
				throw new IOException("Failed to send acknowledgement");
			}

		}
	}

	/**
	 * This method sends the data reception complete message
	 * @param key
	 * @param data
	 * @throws IOException
	 * @author sarat
	 */
	private void sendDataReceptionCompleteMessage(SelectionKey key, byte[] data) throws IOException {
		SocketChannel channel = (SocketChannel)key.channel();
		byte[] ackData = new byte[4];
		ackData[0]=00;
		ackData[1]=00;
		ackData[2]=00;
		ackData[3]=data[9]; //bPacketRec[0];

		ByteBuffer bSend = ByteBuffer.allocate(ackData.length);
		bSend.clear();
		bSend.put(ackData);
		bSend.flip();
		while (bSend.hasRemaining()) {
			try {
				channel.write(bSend);
			} catch (IOException e) {
				throw new IOException("Could not send Data Reception Acknowledgement");
			}
		}
	}

	/**
	 * This generic method is called to process the device input
	 * @param key
	 * @param data
	 * @throws IOException
	 * @author sarat
	 */
	private void processDeviceStream(SelectionKey key, byte[] data) throws IOException{
		SocketChannel channel = (SocketChannel)key.channel();
		String sDeviceID = "";
		// Initial call where the device sends it's IMEI# - Sarat
		if ((data.length >= 10) && (data.length <= 17)) {
			int ii = 0;
			for (byte b : data)
			{
				if (ii > 1) { // skipping first 2 bytes that defines the IMEI length. - Sarat
					char c = (char)b;
					sDeviceID = sDeviceID + c;
				}
				ii++;
			}
			// printing the IMEI #. - Sarat
			System.out.println("IMEI#: "+sDeviceID);

			LinkedList<String> lstDevice = new LinkedList<String>();
			lstDevice.add(sDeviceID);
			dataTracking.put(channel, lstDevice);

			// send message to module that handshake is successful - Sarat
			try {
				sendDeviceHandshake(key, true);
			} catch (IOException e) {
				e.printStackTrace();
			}
			// second call post handshake where the device sends the actual data. - Sarat
		}else if(data.length > 17){
			mClientStatus.put(channel, System.currentTimeMillis());
			List<?> lst = (List<?>)dataTracking.get(channel);
			if (lst.size() > 0)
			{
				//Saving data to database
				sDeviceID = lst.get(0).toString();
				DeviceState.put(channel, sDeviceID);

				//String sData = org.bson.internal.Base64.encode(data);

				// printing the data after it has been received - Sarat
				StringBuilder deviceData = byteToStringBuilder(data);
				System.out.println(" Data received from device : "+deviceData);
				
				try {
					//publish device IMEI and packet data to Nats Server
					natsPublisher.publishMessage(sDeviceID, deviceData);
				} catch(Exception nexp) {
					nexp.printStackTrace();
				}

				//sending response to device after processing the AVL data. - Sarat
				try {
					sendDataReceptionCompleteMessage(key, data);
				} catch (IOException e) {
					e.printStackTrace();
				}
				//storing late timestamp of device
				DeviceLastTimeStamp.put(sDeviceID, System.currentTimeMillis());

			}else{
				// data not good.
				try {
					sendDeviceHandshake(key, false);
				} catch (IOException e) {
					throw new IOException("Bad data received");
				}
			}
		}else{
			// data not good.
			try {
				sendDeviceHandshake(key, false);
			} catch (IOException e) {
				throw new IOException("Bad data received");
			}
		}
	}
}
