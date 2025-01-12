package cross.client;


import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Classe con il metodo main per lanciare il client
 * e gestire una CLI semplificata.
 */
public class ClientMain {
    public static void main(String[] args) {
        if(args.length != 3){
            System.out.println("Usage: java ClientMain <serverIp> <serverPort> <udpPort>");
        }
        String serverIp = args[0];
        int serverPort;
        int udpPort;
        try{
            serverPort = Integer.parseInt(args[1]);
            udpPort = Integer.parseInt(args[2]);
        } catch (NumberFormatException e){
            System.out.println("Usage: serverPort and udpPort must be integers");
            return;
        }
        
        //otteniamo l'IP locale per UDP
        String udpIp;
        try{
            udpIp= InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e){
            udpIp = "127.0.0.1";
        }
        
        CrossClient client = new CrossClient(serverIp, serverPort, udpIp, udpPort);
        client.start();
    }
    
}
