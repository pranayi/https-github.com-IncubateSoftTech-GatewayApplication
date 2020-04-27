package com.incubatesoft.gateway;


public class GatewayApplication 
{
    public static void main( String[] args )
    {
        System.out.println( "Gateway Application !" );        
        
        Thread gateWayThread = new Thread(new DeviceConnectivity());
        gateWayThread.start();                        
    }
}
