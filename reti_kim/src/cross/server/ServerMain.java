package cross.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;
import java.util.Scanner;

public class ServerMain {
    public static void main(String[] args) {
        //carica il file di configurazione;
        Properties props = new Properties();
        try(FileInputStream fis = new FileInputStream("server_config.properties")) {
            props.load(fis);
        }catch(IOException e){
            System.err.println("[ServerMain] Error loading server_config.properties");
            return;
        }
        
        int port = 12345; //default per il caso di fallback
        try{
            port = Integer.parseInt(props.getProperty("port"));
        } catch (NumberFormatException e){
            System.err.println("[ServerMain] Invalid port in server_config.properties");
            return;
        }
        
        CrossServer server;
        Thread serverThread = null;
        try{
            server = new CrossServer(port);
        } catch (IOException e){
            System.out.println("[ServerMain] Server initialization error");
            e.printStackTrace();
            return;
        }
        //avvio server in un thread separato
        CrossServer finalServer = server;
        serverThread = new Thread(() -> {
            try{
                finalServer.start();
            } catch (IOException e) {
                if (finalServer.isRunning()) {
                    System.err.println("[ServerMain] Error during execution of server");
                    e.printStackTrace();
                }
            }
        });
        
        serverThread.start();
        System.out.println("[ServerMain] Server started, to terminate server: 'exit', 'quit' or 'close'");
    //thread principale per ascoltare i comandi della console
    try(Scanner scanner = new Scanner(System.in)){
        while(true){
            String command = scanner.nextLine().trim().toLowerCase();
            if(Arrays.asList("exit", "quit", "close").contains(command)){
                System.out.println("[ServerMain] Server terminated");
                finalServer.stop();
                break;
            } else {
                System.out.println("[ServerMain] Unknown server command: " + command);
                }
            }
        } catch (Exception e){
            System.err.println("[ServerMain] Error during reading command");
            e.printStackTrace();
    }
    
    //attesa per terminazione thread di server
        try{
            serverThread.join();
        } catch (InterruptedException e) {
            System.err.println("[ServerMain] Main thread interrupted");
            e.printStackTrace();
        }
     
        System.out.println("[ServerMain] Server correctly terminated");
    }
}
