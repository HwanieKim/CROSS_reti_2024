package cross.server;
import java.io.IOException;
import java.net.*;

//supporto per inviare pacchetti UDP a un client con ip:port specificati
public class NotificationService {
    
    private final DatagramSocket socket;
    private volatile boolean isRunning;
    
    public NotificationService(DatagramSocket socket) {
        this.socket = socket;
        this.isRunning = true;
    }
    
    //invio notifica testuale (JSON) al cleintNotificationInto speficicato
    public void sendNotificiation(CrossServer.ClientNotificationInfo info, String message){
        if(!isRunning) {
            System.err.println("[NotificationService] Attempt to send notification after termination");
            return;
        }
        try{
            byte[] buf = message.getBytes();
            InetAddress address = InetAddress.getByName(info.ip);
            DatagramPacket packet = new DatagramPacket(buf, buf.length, address, info.port);
            socket.send(packet);
            System.out.println("[NotificationService]: Notification sent to " + info.ip + ":" + info.port);
        } catch (IOException e) {
            if(isRunning) { //verifica che l'errore non sia dovuto dalla terminazione
            System.err.println("[NotificationService] Error sending UDP message to "+ info.ip +": " + info.port);
            e.printStackTrace();
            } else {
                //ignora
            }
        }
    }
    
    //terminazione servizio notifiche, chiusura socket
    public void terminate(){
        isRunning = false;
        socket.close();
        System.out.println("[NotificationService] terminated, socket closed");
    }
}
