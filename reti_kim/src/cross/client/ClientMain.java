package cross.client;


import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;

/**
 * Classe con il metodo main per lanciare il client
 * e gestire una CLI semplificata.
 */
public class ClientMain {
    public static void main(String[] args) {
        //carica il file di configurazione
        Properties props = new Properties();
        try(FileInputStream fis = new FileInputStream("client_config.properties")){
            props.load(fis);
        }catch (IOException e){
            System.err.println("[ClientMain] Error loading client_config.properties");
            return;
        }
        
        
        String serverIp = props.getProperty("serverIp");
        int serverPort;
        int udpPort;
        try{
            serverPort = Integer.parseInt(props.getProperty("serverPort"));
            udpPort = Integer.parseInt(props.getProperty("udpPort"));
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
