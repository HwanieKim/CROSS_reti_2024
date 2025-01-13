package cross.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.*;

public class WorkerThread implements Runnable{
    
    private final Socket clientSocket;
    private final CrossServer server;
    
    private BufferedReader in;
    private PrintWriter out;
    
    public WorkerThread(Socket clientSocket, CrossServer server) {
        this.clientSocket = clientSocket;
        this.server = server;
    }
    
    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            
            //lettura di messaggi dal client
            String line;
            while ((line = in.readLine()) != null) {
                try{
                    JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                    handleRequest(request);
                } catch (Exception e){
                    System.err.println("[WorkerThread - run] Error handling request - " + e.getMessage());
                    e.printStackTrace();
                    JsonObject errorResponse = new JsonObject();
                    errorResponse.addProperty("response", 500);
                    errorResponse.addProperty("errorMessage", "[WorkerThread - run] Internal server error");
                    send(errorResponse);
                }
            }
        } catch (IOException e){
            e.printStackTrace();
        }
            finally {
            //fallback per i client che disconnettono senza fare log out
            String user = server.socketMap.remove(clientSocket);
            if(user != null){
                server.loggedUsers.remove(user);
                System.out.println("[WorkerThread] logged out " + user);
            }
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("[WorkerThread - run] Connection terminated with " + clientSocket.getRemoteSocketAddress());
        }
    }
    
    //decodifica di Json
    private void handleRequest(JsonObject request){
        if (!request.has("operation")){
            //campo operation mancante, non sappiamo cosa fare -> errore
            JsonObject response = new JsonObject();
            response.addProperty("response",103);
            response.addProperty("errorMessage","[WorkerThread - request] Invalid request: insert operation");
            send(response);
            return;
        }
        // prendo tipo di operazione dal json, e passo ad apposito handler definito
        String operation = request.get("operation").getAsString();
        System.out.println("[WorkerThread] operation received " + operation);
        
        switch (operation){
            // ********** GESTIONE UTENTI **********
            
            case "register":
                handleRegister(request);
                break;
            
            case "login":
                handleLogin(request);
                break;
            
            case "updateCredentials":
                handleUpdateCredentials(request);
                break;
            
            case "logout":
                handleLogout(request);
                break;
                
            // ********** GESTIONE ORDINI **********
            
            case "insertLimitOrder":
                handleInsertLimitOrder(request);
                break;
            
            case "insertMarketOrder":
                handleInsertMarketOrder(request);
                break;
            
            case "insertStopOrder":
                handleInsertStopOrder(request);
                break;
                
            case "cancelOrder":
                handleCancelOrder(request);
                break;
                
            // ********** ALTRE OPERAZIONI **********
            
            case "getPriceHistory":
                handlePriceHistory(request);
                break;
                
            default:
                //operazione sconosciuta
                JsonObject response = new JsonObject();
                response.addProperty("response",103);
                response.addProperty("errorMessage","[WorkerThread - request] Invalid request: unknown operation "+ operation);
                send(response);
        }
        
    }
    //----------------------------------------------------------------
    // GESTIONE UTENTI
    //----------------------------------------------------------------
    private void handleRegister(JsonObject request){
            /* register
            {
                "operation": "register",
                "username": "...",
                "password": "..."
            }
            */
        if(!request.has("username") || !request.has("password")){
            JsonObject response = new JsonObject();
            response.addProperty("response",103);
            response.addProperty("errorMessage","[WorkerThread - register] Invalid request: insert username/password");
            send(response);
            return;
        }
        String username = request.get("username").getAsString();
        String password = request.get("password").getAsString();
        
        JsonObject response = server.handleRegister(username, password);

        send(response);
        
    }
    private void handleLogin(JsonObject request){
            /* login
            {
                "operation": "login",
                "username": "...",
                "password": "..."
            }
            */
        //controllo esistenza utente
        if(!request.has("username") || !request.has("password")){
            JsonObject response = new JsonObject();
            response.addProperty("response",103);
            response.addProperty("errorMessage","[WorkerThread - register] Invalid request: insert username/password");
            send(response);
            return;
        }
        String username = request.get("username").getAsString();
        String password = request.get("password").getAsString();
        String udpIp = request.get("udpIp").getAsString();
        int udpPort = request.get("udpPort").getAsInt();
        
        JsonObject response = server.handleLogin(username, password, udpIp, udpPort);
        
        //se il login ha successo, mappa il socket all'utente
        if(response.get("response").getAsInt() == 100){
            server.socketMap.put(clientSocket,username);
        }
        send(response);
    }
    
    private void handleLogout(JsonObject request) {
            /* logout
            {
                "operation": "logout"
            }
            */
        JsonObject response = new JsonObject();
        
        String username = server.socketMap.get(clientSocket);
        if (username == null) {
            //nessun username associato a questo socket
            response.addProperty("response", 101);
            response.addProperty("errorMessage", "[WorkerThread - login] Username/connection mismatch or non existent username or user not logged in or other error cases");
        } else{
            response = server.handleLogout(username);
        }
        
        send(response);
    }
    private void handleUpdateCredentials(JsonObject request){
            /* update
            {
                "operation": "updateCredentials",
                "username": "...",
                "oldPassword": "..."
                "newPassword": "..."
            }
            */
        
        //controllo campi mancanti
        if(!request.has("username") || !request.has("oldPassword") || !request.has("newPassword")){
            JsonObject response = new JsonObject();
            response.addProperty("response",105);
            response.addProperty("errorMessage","[WorkerThread - update] Invalid request: insert username or old password or new password");
            send(response);
            return;
        }
        
        String username = request.get("username").getAsString();
        String oldPassword = request.get("oldPassword").getAsString();
        String newPassword = request.get("newPassword").getAsString();
        
        //verifica che l'utente sia autenticato e che l'username corrisponda all'utente autenticato
        String authenticatedUser = server.socketMap.get(clientSocket);
        if(authenticatedUser == null){
            JsonObject response = new JsonObject();
            response.addProperty("response",105);
            response.addProperty("errorMessage", "[WorkerThread - update] User not logged in");
            send(response);
            return;
        }
        
        if(!username.equals(authenticatedUser)){
            JsonObject response = new JsonObject();
            response.addProperty("response",102);
            response.addProperty("errorMessage", "[WorkerThread - update] Username mismatch");
            send(response);
            return;
        }
        
        JsonObject response = server.handleUpdateCredentials(username, oldPassword, newPassword);
        send(response);
    }
    
    
    // ----------------------------------------------------------------
    // GESTIONE ORDINI
    // ----------------------------------------------------------------
    private void handleInsertLimitOrder(JsonObject request){
            /* insertLimitOrder
            {
                "operation": "insertLimitOrder",
                "type": "...(ask/bid)",
                "size": "..."
                "price": "..."
            }
            */
        JsonObject response = new JsonObject();
        if(!request.has("type")||!request.has("size")||!request.has("price")){
            response.addProperty("orderId",-1);
            send(response);
            return;
        }
        
        
        String type = request.get("type").getAsString();
        int size;
        int price;
        try {
            size = request.get("size").getAsInt();
            price = request.get("price").getAsInt();
        } catch (NumberFormatException e) {
            response.addProperty("orderId",-1);
            send(response);
            return;
        }
        
        // Validazione del tipo di ordine
        if(!type.equals("bid") && !type.equals("ask")){
            response.addProperty("response", 102);
            response.addProperty("errorMessage", "[WorkerThread - insertLimit] Invalid type: should be 'bid' or 'ask'");
            send(response);
            return;
        }
        
        // Validazione della dimensione dell'ordine
        if(size <= 0){
            response.addProperty("response", 104);
            response.addProperty("errorMessage", "[WorkerThread - insertLimit] Invalid size: must be a positive integer");
            send(response);
            return;
        }
        String user = server.socketMap.get(clientSocket);
        if(user == null){
            response.addProperty("orderId",-1);
            send(response);
            return;
        }
        //creazione ordine
        int orderId = server.getNextOrderId();
        
        Order order = new Order(orderId,type,size,price,"limit",user);
        
        server.insertLimitOrder(order);
        
        response.addProperty("orderId",order.getOrderId());
        send(response);
    }
    
    private void handleInsertMarketOrder(JsonObject request){
        /* insertMarketOrder
            {
                "operation": "insertMarketOrder",
                "type": "...(ask/bid)",
                "size": "..."
             }
            */
        JsonObject response = new JsonObject();
        
        if(!request.has("type") || !request.has("size")){
            response.addProperty("orderId",-1);
            send(response);
            return;
        }
        
        String type = request.get("type").getAsString();
        int size;
        try {
            size = request.get("size").getAsInt();
        }catch (NumberFormatException e){
            response.addProperty("orderId",-1);
            send(response);
            return;
        }
        
        String user = server.socketMap.get(clientSocket);
        if(user == null){
            response.addProperty("orderId",-1);
            send(response);
            return;
        }
        
        int orderId = server.getNextOrderId();
        Order order = new Order(orderId,type,size,0,"market",user);
        
        boolean fullyExecuted = server.insertMarketOrder(order);
        if(fullyExecuted){
            response.addProperty("orderId",orderId);
        } else {
            response.addProperty("orderId",-1);
        }
        send(response);
    }
    
    private void handleInsertStopOrder(JsonObject request){
          /* insertStopOrder
            {
                "operation": "insetStopOrder",
                "type": "...(ask/bid)",
                "size": "..."
                "price": "..."
            }
            */
        
        JsonObject response = new JsonObject();
        if(!request.has("type") || !request.has("size") || !request.has("stopPrice")){
            System.err.println("[WorkerThread - insertStop] Missing required fields.");
            response.addProperty("orderId",-1);
            send(response);
            return;
        }
        
        String type = request.get("type").getAsString();
        int size;
        int stopPrice;
        try{
            size = request.get("size").getAsInt();
            stopPrice = request.get("stopPrice").getAsInt();
        } catch (NumberFormatException e){
            response.addProperty("orderId",-1);
            send(response);
            return;
        }
        
        String user = server.socketMap.get(clientSocket);
        if(user == null){
            response.addProperty("orderId",-1);
            send(response);
            return;
        }
        
        int orderId = server.getNextOrderId();
        Order order = new Order(orderId,type,size,stopPrice,"stop",user);
        
        server.insertStopOrder(order);
        
        response.addProperty("orderId",orderId);
        send(response);
    }
    
    private void handleCancelOrder(JsonObject request){
          /* cancel
            {
                "operation": "cancelOrder",
                "orderId": "..."
            }
            */
        JsonObject response = new JsonObject();
        if(!request.has("orderId")){
            response.addProperty("response",101);
            response.addProperty("errorMessage","[WorkerThread - cancel] Invalid request: missing orderId");
            send(response);
            return;
        }
        
        int orderId;
        try{
            orderId = request.get("orderId").getAsInt();
        } catch (NumberFormatException e){
            response.addProperty("response",101);
            response.addProperty("errorMessage","[WorkerThread - cancel] Invalid request: missing orderId");
            send(response);
            return;
        }
        
        String user = server.socketMap.get(clientSocket);
        if(user == null){
            response.addProperty("response",101);
            response.addProperty("errorMessage","[WorkerThread - cancel] Invalid request: user not logge in");
            send(response);
            return;
        }
        
        boolean ok = server.cancelOrder(orderId);
        if(ok){
            response.addProperty("response",100);
            response.addProperty("errorMessage", "OK");
        } else {
            response.addProperty("response",101);
            response.addProperty("errorMessage", "[WorkerThread - cancel] Cancel failed");
        }
        send(response);
    }
    // ----------------------------------------------------------------
    // ALTRE OPERAZIONI
    // ----------------------------------------------------------------
    private void handlePriceHistory(JsonObject request){
        JsonObject response = new JsonObject();
        
        if(!request.has("monthYear")){
            response.addProperty("response", 103);
            response.addProperty("errorMessage", "[WorkerThread - history] Missing parameter: monthYear is required");
            send(response);
            return;
        }
        
        String monthYear = request.get("monthYear").getAsString().trim();
        
        // Rimuovi eventuali virgolette singole o doppie
        monthYear = monthYear.replaceAll("^['\"]|['\"]$", "");
        
        // Verifica la lunghezza
        if(monthYear.length() != 6){
            response.addProperty("response", 104);
            response.addProperty("errorMessage", "[WorkerThread - history] Invalid month format: should be 'MMYYYY', e.g., '012025'");
            send(response);
            return;
        }
        
        // Parsing del mese e dell'anno
        String mmString = monthYear.substring(0,2);
        String yyyyString = monthYear.substring(2);
        
    
        int month;
        try{
            month = Integer.parseInt(mmString);
        } catch (NumberFormatException e){
            response.addProperty("response", 105);
            response.addProperty("errorMessage", "[WorkerThread - history] Month and Year must be integers");
            send(response);
            return;
        }
        
        if (month <1 || month >12){
            response.addProperty("response", 106);
            response.addProperty("errorMessage", "[WorkerThread - history] Invalid month value: must be between 01 and 12");
            send(response);
            return;
        }
        
        //verifica utente autenticato
        String user = server.socketMap.get(clientSocket);
        if(user == null){
            response.addProperty("response", 107);
            response.addProperty("errorMessage", "[WorkerThread - history] User not logged in");
            send(response);
            return;
        }
        
        Map<String, CrossServer.OHLC> history = server.getPriceHistory(monthYear);
        
        if(history.isEmpty()){
            response.addProperty("response", 107);
            response.addProperty("errorMessage", "[WorkerThread - history] No data available for the specified month and year");
        } else {
            response.addProperty("response", 100);
            JsonObject historyJson = server.getGson().toJsonTree(history).getAsJsonObject();
            response.add("priceHistory", historyJson);
        }
        
        send(response);
        
    }

    private void send(JsonObject object){
        out.println(object.toString());
        out.flush();
    }
    
}
