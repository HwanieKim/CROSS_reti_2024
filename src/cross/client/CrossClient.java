package cross.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.Map;

public class CrossClient {
    private final String serverIp;
    private final int serverPort;
    private Socket tcpSocket;
    private BufferedReader in;
    private PrintWriter out;
    
    private final Gson gson;
    
    private final String udpIp; //Ip del client
    private final int udpPort; // porta UDP su cui il client ascolta
    
    private DatagramSocket udpSocket;
    private NotificationListener notificationListener;
    //costruttore
    public CrossClient(String serverIp, int serverPort, String udpIp, int udpPort) {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.udpIp = udpIp;
        this.udpPort = udpPort;
        this.gson = new Gson();
    }
    //avvio client: connessione TCP e UDP, avvio del listener UDP
    public void start() {
        try{
            //connessione TCP al server
            tcpSocket = new Socket(serverIp,serverPort);
            in = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
            out = new PrintWriter(tcpSocket.getOutputStream(), true);
            System.out.println("[CrossClient] Connected to TCP server " + serverIp + ":" + serverPort);
            
            //setup UDP socket
            udpSocket = new DatagramSocket(udpPort);
            System.out.println("[CrossClient] UDP socket opened on port " + udpPort);
            
            //avvio notificationListener per le notifiche UDP
            notificationListener = new NotificationListener(udpSocket);
            Thread listnerThread = new Thread(notificationListener);
            listnerThread.start();
            System.out.println("[CrossClient] LISTENER UDP thread started");
            
            //inizializzazione dell'interfaccia CLI
            runCLI();
        } catch (IOException e){
            System.err.println("[crossClient] Connection error: " + e.getMessage());
            e.printStackTrace();
        } finally{
            //chiusura
            try{
                if(udpSocket != null && !udpSocket.isClosed()){
                    udpSocket.close();
                }
                if(tcpSocket != null && !tcpSocket.isClosed()){
                    tcpSocket.close();
                }
            } catch (IOException e){
                e.printStackTrace();
            }
        }
    }
   
    
    //esecuzione Command Line Interface (CLI)
    private void runCLI(){
        Scanner scanner = new Scanner(System.in);
        System.out.println("Welcome to Cross Client");
        printHelp();
        
        while(true){
            System.out.print("> ");
            String line = scanner.nextLine();
            if(line.trim().isEmpty()) continue;
            
            String[] tokens = line.split("\\s+"); //spaces
            String command = tokens[0].toLowerCase();
            
            try{
                switch (command){
                    case "help":
                        printHelp();
                        break;
                    case "register":
                        handleRegister(tokens);
                        break;
                    case "login":
                        handleLogin(tokens);
                        break;
                    case "logout":
                        handleLogout();
                        break;
                    case "update":
                        handleUpdateCredentials(tokens);
                        break;
                    case "limit":
                        handleInsertLimitOrder(tokens);
                        break;
                    case "market":
                        handleInsertMarketOrder(tokens);
                        break;
                    case "stop":
                        handleInsertStopOrder(tokens);
                        break;
                    case "cancel":
                        handleCancelOrder(tokens);
                        break;
                    case "history":
                        handleGetPriceHistory(tokens);
                        break;
                    case "exit":
                        System.out.println("Exit from Cross Client.");
                        return;
                        default:
                            System.out.println("Unknown command, type 'help' for supported commands" );
                }
            }catch (Exception e){
                System.err.println("[CrossClient] error during executing command: "+e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    //stampa comandi disponibili
    private void printHelp(){
        System.out.println("Comandi disponibili:");
        System.out.println("  help");
        System.out.println("  register <username> <password>");
        System.out.println("  login <username> <password>");
        System.out.println("  logout");
        System.out.println("  update <username> <old_password> <new_password>");
        System.out.println("  limit <ask/bid> <size> <price>");
        System.out.println("  market <ask/bid> <size>");
        System.out.println("  stop <ask/bid> <size> <stop_price>");
        System.out.println("  cancel <orderId>");
        System.out.println("  history <MMYYYY>");
        System.out.println("  exit");
    }
    
    
    //registrazione user
    public void handleRegister(String[] token){
        if(token.length != 3){
            System.out.println("Corrected Usage: register <username> <password>");
            return;
        }
        
        String username = token[1];
        String password = token[2];
        
        //costruzione di json
        JsonObject request = new JsonObject();
        request.addProperty("operation", "register");
        request.addProperty("username", username);
        request.addProperty("password", password);
        
        sendRequest(request);
        JsonObject response = receiveResponse();
        if(response!=null){
            handleResponse(response);
        }
    }
    
    //login
    public void handleLogin(String[]token){
        if(token.length != 3){
            System.out.println("Corrected Usage: login <username> <password>");
            return;
        }
        String username = token[1];
        String password = token[2];
       
        JsonObject request = new JsonObject();
        request.addProperty("operation", "login");
        request.addProperty("username", username);
        request.addProperty("password", password);
        request.addProperty("udpIp", udpIp);
        request.addProperty("udpPort", udpPort);
        
        sendRequest(request);
        JsonObject response = receiveResponse();
        
        if(response!=null){
            handleResponse(response);
        }
    }
    
    
    public void handleLogout() {
        JsonObject request = new JsonObject();
        request.addProperty("operation", "logout");
        
        sendRequest(request);
        JsonObject response = receiveResponse();
        if(response!=null){
            handleResponse(response);
        }
        
    }
    public void handleUpdateCredentials(String[] token) {
        if(token.length != 4){
            System.out.println("Corrected Usage: update <username> <old_password> <new_password>");
            return;
        }
        
        String username = token[1];
        String oldPassword = token[2];
        String newPassword = token[3];
        
        JsonObject request = new JsonObject();
        request.addProperty("operation", "updateCredentials");
        request.addProperty("username", username);
        request.addProperty("oldPassword", oldPassword);
        request.addProperty("newPassword", newPassword);
        
        sendRequest(request);
        JsonObject response = receiveResponse();
        if(response!=null){
            handleResponse(response);
        }
    }
    
    public void handleInsertLimitOrder (String[] token){
        if(token.length != 4){
            System.out.println("Corrected Usage: limit <ask/bid> <size> <price>");
            return;
        }
        String type = token[1].toLowerCase();
        if(!type.equals("ask") && !type.equals("bid")){
            System.out.println("invalid type, should be 'ask' or 'bid'");
            return;
        }
        int size;
        int price;
        try{
            size = Integer.parseInt(token[2]);
            price = Integer.parseInt(token[3]);
        } catch (NumberFormatException e){
            System.out.println("[CrossClient - insertLimit] size and price must be an integer");
            return;
        }
        
        JsonObject request = new JsonObject();
        request.addProperty("operation", "insertLimitOrder");
        request.addProperty("type", type); //ask or bid
        request.addProperty("size", size);
        request.addProperty("price", price);
        
        sendRequest(request);
        JsonObject response = receiveResponse();
        
        if(response != null){
            if(response.has("orderId")){
            int orderId = response.get("orderId").getAsInt();
            if(orderId == -1){
                System.out.println("[CrossClient - insertLimit] Error: limit order insertion failed");
            } else {
                System.out.println("[CrossClient - insertLimit] Limit Order inserted with ID: " + orderId);
            }
        } else {
           handleResponse(response);
            }
        }
    }
    public void handleInsertMarketOrder(String[] token) {
        if(token.length != 3){
            System.out.println("Corrected Usage: market <ask/bid> <size>");
            return;
        }
        String type = token[1].toLowerCase();
        if(!type.equals("ask") && !type.equals("bid")){
            System.out.println("invalid type, should be 'ask' or 'bid'");
            return;
        }
        int size;
        try{
            size = Integer.parseInt(token[2]);
        } catch (NumberFormatException e){
            System.out.println("[CrossClient - insertMarket] size must be an integer");
            return;
        }
     
        JsonObject request = new JsonObject();
        request.addProperty("operation", "insertMarketOrder");
        request.addProperty("type", type);
        request.addProperty("size", size);
        
        sendRequest(request);
        JsonObject response = receiveResponse();
        if(response != null) {
            if (response.has("orderId")) {
                int orderId = response.get("orderId").getAsInt();
                if (orderId == -1) {
                    System.out.println("[CrossClient - insertMarket] Error: market order insertion failed (not completely executable)");
                } else {
                    System.out.println("[CrossClient - insertMarket] Market Order inserted with ID: " + orderId);
                }
            } else {
                handleResponse(response);
            }
        }
    }
    public void handleInsertStopOrder(String[] token) {
        if(token.length != 4){
            System.out.println("Corrected Usage: stop <ask/bid> <size> <stop_price>");
            return;
        }
        String type = token[1].toLowerCase();
        if(!type.equals("ask") && !type.equals("bid")){
            System.out.println("invalid type, should be 'ask' or 'bid'");
            return;
        }
        
        int size;
        int stopPrice;
        try{
            size = Integer.parseInt(token[2]);
            stopPrice = Integer.parseInt(token[3]);
        } catch (NumberFormatException e){
            System.out.println("[CrossClient - insertStop] size and stopPrice must be an integer");
            return;
        
        }
        JsonObject request = new JsonObject();
        request.addProperty("operation", "insertStopOrder");
        request.addProperty("type", type);
        request.addProperty("size", size);
        request.addProperty("stopPrice", stopPrice);
        
        sendRequest(request);
        JsonObject response = receiveResponse();
        
        if(response != null) {
            if (response.has("orderId")) {
                int orderId = response.get("orderId").getAsInt();
                if (orderId == -1) {
                    System.out.println("[CrossClient - insertStop] Error: stop order insertion failed");
                } else {
                    System.out.println("[CrossClient - insertStop] Stop Order inserted with ID: " + orderId);
                }
            } else {
                handleResponse(response);
            }
        }
    }
    
    public void handleCancelOrder(String[] token) {
        if(token.length != 2){
            System.out.println("Corrected Usage: cancel <orderId>");
            return;
        }
        int orderId;
        try{
            orderId = Integer.parseInt(token[1]);
        } catch (NumberFormatException e){
            System.out.println("orderId must be an integer");
            return;
        }
        
        JsonObject request = new JsonObject();
        request.addProperty("operation", "cancelOrder");
        request.addProperty("orderId", orderId);

        sendRequest(request);
        JsonObject response = receiveResponse();
        if(response != null){
            handleResponse(response);
        }
    }
    
    
    public void handleGetPriceHistory(String[] token) {
        if(token.length != 2){
            System.out.println("Corrected Usage: history <MMYYYY>");
            return;
        }
        String monthYear = token[1];
        
        // Correzione della condizione di validazione
        if(!monthYear.matches("^[0-9]{6}$")){
            System.out.println("Invalid month format: should be 'MMYYYY', es. '012025'");
            return;
        }
        
        JsonObject request = new JsonObject();
        
        // Correzione del nome della proprietà
        request.addProperty("operation", "getPriceHistory");
        request.addProperty("monthYear", monthYear);
        
        sendRequest(request);
        JsonObject response = receiveResponse();
        
        if(response != null){
            if(response.has("response")){
                int responseCode = response.get("response").getAsInt();
                String errorMsg = response.has("errorMessage") ? response.get("errorMessage").getAsString() : "";
                if(responseCode == 100){
                    if (response.has("priceHistory")){
                        JsonObject historyObj = response.getAsJsonObject("priceHistory");
                        System.out.println("storico prezzi per " + monthYear + ":");
                        for(Map.Entry<String, JsonElement> entry : historyObj.entrySet()){
                            String day = entry.getKey();
                            JsonObject ohlc = entry.getValue().getAsJsonObject();
                            int open = ohlc.get("open").getAsInt();
                            int close = ohlc.get("close").getAsInt();
                            int high = ohlc.get("high").getAsInt();
                            int low = ohlc.get("low").getAsInt();
                            System.out.println("  " + day + " | Open: " + open + " | Close: " + close + " | High: " + high + " | Low: " + low);
                        }
                    } else {
                        System.out.println("[CrossClient - history] no trades available for " + monthYear + ".");
                    }
                } else {
                    System.out.println("[CrossClient - history] error: (" + responseCode + ") " + errorMsg);
                }
            } else {
                handleResponse(response);
            }
        }
    }
    
    
    //handling server response
    private void handleResponse (JsonObject response) {
        if(!response.has("response") || !response.has("errorMessage")){
            System.out.println("[CrossClient] Invalid response from server");
            return;
        }
        int respCode = response.get("response").getAsInt();
        String respMessage = response.get("errorMessage").getAsString();
        if(respCode == 100){
            System.out.println("[CrossClient] Operation successful");
        } else {
            System.out.println("error ("+respCode+"): "+ respMessage);
        }
    }
    //invio richiesta JSON al server
    private void sendRequest(JsonObject request){
        out.println(request.toString());
        out.flush();
    }
    
    //ricezione risposta JSON dal server
    private JsonObject receiveResponse(){
        try{
            String line = in.readLine();
            if(line == null){
                System.out.println("[CrossClient] connection closed from server");
                System.exit(0);
            }
            return JsonParser.parseString(line).getAsJsonObject();
        } catch (IOException e) {
            System.err.println("[CrossClient] response receiving error: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    //---- handling notification ----
        private record NotificationListener(DatagramSocket socket) implements Runnable {
        @Override
            public void run() {
                byte[] buf = new byte[65535]; //dimensione massima per un pacchetto UDP (16 bit), inclusi header e payload
                while (!socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(packet);
                        String msg = new String(packet.getData(), 0, packet.getLength());
                        handleNotification(msg);
                    } catch (IOException e) {
                        if (socket.isClosed()) {
                            break;
                        }
                        System.err.println("[CrossClient - NotificationListener] error in receiving UDP: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        
        //gestione notifica ricevuta via UDP
            private void handleNotification(String msg) {
                //le notifiche dal server sono JSON con un array di "TRADES"
                // ogni trade ha: orderId, type, orderType, size, price, timestamp
                try {
                    JsonObject json = JsonParser.parseString(msg).getAsJsonObject();
                    if (!json.has("trades")) {
                        System.err.println("[CrossClient - handleNotification] error in JSON: doesnt' exists 'trades'");
                        return;
                    }
                    var trades = json.getAsJsonArray("trades");
                    for (var tradeElement : trades) {
                        JsonObject trade = tradeElement.getAsJsonObject();
                        int orderId = trade.get("orderId").getAsInt();
                        String type = trade.get("type").getAsString();
                        String orderType = trade.get("orderType").getAsString();
                        int size = trade.get("size").getAsInt();
                        int price = trade.get("price").getAsInt();
                        long timestamp = trade.get("timestamp").getAsLong();
                        
                        System.out.println("\n[TRADE NOTIFICATION] order Id: " + orderId + " | Tipo " + type + " | Order Type " + orderType + " | size " + size + " | Price " + price + " | Timestamp " + timestamp);
                        System.out.print("> ");
                    }
                } catch (Exception e) {
                    System.out.println("[CrossClient - handleNotification] error in notification handling: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
}
