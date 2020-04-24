package com.incubatesoft.gateway;


public class GatewayApplication 
{
    public static void main( String[] args )
    {
        System.out.println( "Gateway Application !" );        
        
        Thread serv = new Thread(new DeviceConnectivity());
        serv.start();                        
    }
}
