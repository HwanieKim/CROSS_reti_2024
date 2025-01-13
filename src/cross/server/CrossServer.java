package cross.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import cross.utils.JsonPersistence;

import java.io.*;
import java.net.ServerSocket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


public class CrossServer {
    
    private final int port;
    private ServerSocket serverSocket;
    private final ExecutorService threadPool;
    
    //serializzazione/deserializzazione JSON
    protected final Gson gson;
    
    //mapping utenti
    protected final Map<String,String> userMap; //mappa username-> password per registrazione.
    protected final Set<String> loggedUsers; // set di utenti loggati
    
    //mappatura per associare un socket all'username loggato
    protected final Map<Socket,String> socketMap;
    
    //strutture ordini
    //Order Book
    protected final ConcurrentSkipListMap<Integer,ConcurrentLinkedQueue<Order>> askBook;
    protected final ConcurrentSkipListMap<Integer,ConcurrentLinkedQueue<Order>> bidBook;
    
    private int currentOrderId; // contatore id
    protected final List<Order> stopOrders; //stop order memorizzati in una lista
    
    //notifiche: manteniamo user-> (ip, porta udp)
    //quando fa login, comunica al server dove inviargli le notifiche
    protected Map<String,ClientNotificationInfo> clientNotifications;
    
    //last price, usato per controllare stop
    protected int lastPrice;
    
    //invio pacchetti UDP
    protected final NotificationService notificationService;
    
    //persistenza
    private static final String USER_FILE = "../data/users.json";
    private static final String TRADES_FILE = "../data/trades.json";
    
    //storico esecuzioni
    protected final List<Trade> executedTrades;
    
    //flag per gestione stato server
    private volatile boolean isRunning;
    //true per fairness, first-come, first-served
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock(true);
    
    public CrossServer(int port) throws IOException {
        this.port = port;
        this.threadPool = Executors.newCachedThreadPool();
        this.gson = new Gson();
        
        this.userMap = new ConcurrentHashMap<>();
        this.loggedUsers = ConcurrentHashMap.newKeySet();
        
        this.socketMap = new ConcurrentHashMap<>();
        
        this.currentOrderId = 1; //iniziamo con ID = 1
        this.lastPrice = 1;
        
        askBook = new ConcurrentSkipListMap<>();
        bidBook = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
        stopOrders = Collections.synchronizedList(new ArrayList<>());
        
        //caricamento utenti e trades dal file JSON
        JsonPersistence.loadUsers(USER_FILE,userMap);
        this.executedTrades = JsonPersistence.loadTrades(TRADES_FILE);
        
        //mappatura user-> ip/porta
        this.clientNotifications = new ConcurrentHashMap<>();
        
        //creazione DatagramSoket su una porta casuale per inviare paccetti
        DatagramSocket udpSocket = new DatagramSocket();
        notificationService = new NotificationService(udpSocket);
        
        System.out.println("CROSS SERVER STARTED, Uploaded " + userMap.size() + " users and "+ executedTrades.size() + " executed trades.");
        
        this.isRunning = true;
        
    }
  
    //avvio del server
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        System.out.println("[CrossServer - start] Server start on port " + port);
        System.out.println("[CrossServer - start] Uploaded "+ userMap.size() + " users and "+ executedTrades.size() + " executed trades.");
        
        //salvataggio dati su file JSON ogni 30 secondi, singolo thread dedicato che viene riutilizzato per ogni chiamata del persistData
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::persistData, 30, 90, TimeUnit.SECONDS);
        
        
        try{
            while(isRunning) {
                try{
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("[CrossServer - start] New connection with" + clientSocket.getRemoteSocketAddress());
                    WorkerThread workerThread = new WorkerThread(clientSocket,this);
                    threadPool.submit(workerThread);
                } catch (IOException e) {
                    if(isRunning) {
                        System.err.println("[CrossServer - start] Error during accepting connection");
                        e.printStackTrace();
                    }
                    //se non e' in esecuzione, allora prob che e' stato chiuso il serverSocket
                }
        }
        
        } finally {
            shutdown(scheduler);
        }
    }
    
    //salvataggio dati su file JSON (utenti e trades eseguiti)
    private void persistData() {
        rwLock.writeLock().lock();
        try {
            JsonPersistence.saveUsers(USER_FILE, userMap);
            JsonPersistence.saveTrades(TRADES_FILE, executedTrades);
            System.out.println("[CrossServer - persistData] SERVER: COMPLETED PERIODIC DATA SAVE");
        } finally{
            rwLock.writeLock().unlock();
        }
    }
    
    //stop server
    public void stop(){
        isRunning = false;
        try{
            if(serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            threadPool.shutdownNow();
            notificationService.terminate();
            System.out.println("[CrossServer - stop] Server correctly stopped");
        } catch (IOException e){
            System.err.println("[CrossServer - stop] Error during shutdown of server");
            e.printStackTrace();
        }
    }
    
    //gestione chiusura risorse
    private void shutdown(ScheduledExecutorService scheduler) {
        try{
            if(scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdownNow();
            }
            threadPool.shutdownNow();
            notificationService.terminate();
            System.out.println("[CrossServer - shutdown] Server correctly shut down");
        } catch (Exception e){
            System.err.println("[CrossServer - shutdown] Error during shutdown of resources");
            e.printStackTrace();
        }
    }
    
    //verifica esecuzione server
    public boolean isRunning(){
        return isRunning;
    }
    protected synchronized int getNextOrderId(){
        return currentOrderId++;
    }
    
    public JsonObject handleRegister(String username, String password){
        JsonObject response = new JsonObject();
        
        //validazione password: almeno 6 caratteri/ almeno un numero e un carattere speciale
        if(password.length()<6 || !password.matches("^(?=.*[0-9])(?=.*[!@#$%^&*]).+$")){
            response.addProperty("response",101);
            response.addProperty("errorMessage", " [CrossServer - register] Invalid password: must be at least 6 characters and include a number and a special character");
            return response;
        }
        
        rwLock.writeLock().lock();
        try{
            // Tentativo atomico di aggiunta utente
            String previousValue = userMap.putIfAbsent(username, password);
            if (previousValue != null) {
                response.addProperty("response", 102);
                response.addProperty("errorMessage", "[CrossServer - register] Username unavailable");
                return response;
            }
        } finally {
            rwLock.writeLock().unlock();
        }
        
        response.addProperty("response",100);
        response.addProperty("errorMessage", "OK");
        
        //salva gli utenti aggiornati
        persistData();
        
        return response;
    }
    
    
    public JsonObject handleLogin(String username, String password, String udpIp, int udpPort){
        JsonObject response = new JsonObject();
        
        //acquisizione readLock prima di lettura
        rwLock.readLock().lock();
        try{
            //verifica esistenza utente/validita' password
            if(!userMap.containsKey(username) || !userMap.get(username).equals(password)) {
                response.addProperty("response",101);
                response.addProperty("errorMessage", "[CrossServer - login] Username/password mismatch");
                return response;
            }
        } finally {
            rwLock.readLock().unlock();
        }
        
        //verifica se l'utente e' gia' loggato se no, aggiungi. concurrentHashMap.newKeySet().add funziona come putIfAbsent
        if(!loggedUsers.add(username)) {
            response.addProperty("response",102);
            response.addProperty("errorMessage", "[CrossServer - login] Username already logged in");
            return response;
        }
        
        //registra info per notifica
        registerNotification(username,udpIp,udpPort);
        
        response.addProperty("response",100);
        response.addProperty("errorMessage", "OK");
        
        //salva dati aggiornati
        persistData();
        
        return response;
    }
    
    public JsonObject handleLogout(String username){
        JsonObject response = new JsonObject();
        
        //verifica utente loggato ed eventuale rimozione
        if(!loggedUsers.remove(username)) {
            response.addProperty("response",105);
            response.addProperty("errorMessage", "[CrossServer - logout] Username not logged in");
            return response;
        }
        
        //rimozione info per notifica
        clientNotifications.remove(username);
        
        response.addProperty("response",100);
        response.addProperty("errorMessage", "OK");
        
        persistData();
        return response;
    }
    
    
    public JsonObject handleUpdateCredentials(String username, String oldPassword, String newPassword){
        JsonObject response = new JsonObject();
        
        rwLock.writeLock().lock();
        try{
            //controllo password nuova
            if(newPassword.equals(oldPassword)) {
                response.addProperty("response",103);
                response.addProperty("errorMessage", "[CrossServer - update] New password cannot be the same as the old password");
                return response;
            }
            
            //validazione nuova password
            if(newPassword.length() < 6 || !newPassword.matches("^(?=.*[0-9])(?=.*[!@#$%^&*]).+$")){
                response.addProperty("response",101);
                response.addProperty("errorMessage", "[CrossServer - update] Invalid new password: must be at least 6 characters and include a number and a special character");
                return response;
            }
         
            
            //verifica utente loggato
            if(loggedUsers.contains(username)) {
                response.addProperty("response",104);
                response.addProperty("errorMessage", "[CrossServer - update] Username currently logged in");
                return response;
            }
            boolean updated = userMap.replace(username,oldPassword,newPassword);
            if(!updated){
                response.addProperty("response",102);
                response.addProperty("errorMessage", "OK");
                return response;
            }
            response.addProperty("response",100);
            response.addProperty("errorMessage", "[CrossServer - update] Username or old password mismatch");
            
            
            persistData();
            
            return response;
            
            } finally {
            rwLock.writeLock().unlock();
        }
    }
    public void insertLimitOrder(Order order){
        rwLock.writeLock().lock();
        try{
            matchLimitOrder(order);
            if(order.getRemainingSize() > 0){
            addToBook(order);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    
    public boolean insertMarketOrder(Order order){
        rwLock.writeLock().lock();
        try {
            //verifica se il marketOder puo' essere eseguito completamente
            int totalAvailableSize = 0;
            if (order.getType().equals("ask")) {
                for (Map.Entry<Integer, ConcurrentLinkedQueue<Order>> entry : bidBook.entrySet()) {
                    for (Order bidOrder : entry.getValue()) {
                        totalAvailableSize += bidOrder.getRemainingSize();
                        if (totalAvailableSize >= order.getSize()) {
                            break;
                        }
                    }
                    if (totalAvailableSize >= order.getSize()) {
                        break;
                    }
                }
            } else { //lato bid
                for (Map.Entry<Integer, ConcurrentLinkedQueue<Order>> entry : askBook.entrySet()) {
                    for (Order askOrder : entry.getValue()) {
                        totalAvailableSize += askOrder.getRemainingSize();
                        if (totalAvailableSize >= order.getSize()) {
                            break;
                        }
                    }
                    if (totalAvailableSize >= order.getSize()) {
                        break;
                    }
                }
            }
            if (totalAvailableSize < order.getSize()) {
                //ordine scartato
                System.out.println("[CrossServer - insertMarket] Market Order discarded: size not fully executable");
                return false;
            }
            matchLimitOrder(order);
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    
    public void insertStopOrder(Order order){
        rwLock.writeLock().lock();
        try{
            stopOrders.add(order);
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    
    // rimuove un ordine (non eseguito) dal book o dalla lista stop.
    // ritorna true se trovato e rimosso, altrimenti false
    public boolean cancelOrder(int orderId) {
        rwLock.writeLock().lock();
        try {
            //dalla book
            boolean removed = removeFromBook(orderId, askBook);
            if (!removed) removed = removeFromBook(orderId, bidBook);
            if (!removed) removed = removeFromStopOrders(orderId); //dalla list stop
            return removed;
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    
    
    /*matching di limit order
    * selling: type = ask, to be get matched, best bidding(buying) price should be greater or equal than asking(selling) price,
    * therefore bestBid >= askPrice
    *
    * buying: type = bid, to be get matched, best asking(selling) price should be lesser or equal than bidding(buying) price,
    * therefor bestAsk <= bidPrice
    */
    private void matchLimitOrder(Order incoming){
        //lock gia' acquisita dal chiamante (writelock)
        //caso ask (vendita)
        if(incoming.getType().equals("ask")){
            while(incoming.getRemainingSize() > 0) {
                //finche esiste ordine, vado a prendere il primo nell bidBook (quindi matching con il valore massimo di bid (offerta))
                Map.Entry<Integer, ConcurrentLinkedQueue<Order>> bestBidEntry = bidBook.firstEntry();
                if (bestBidEntry == null) break; //no bid
                
                int bestBidPrice = bestBidEntry.getKey();
                if (bestBidPrice < incoming.getPrice()) {
                    //matching avviene sse il prezzo di vendita (ask, incoming.getPrice()) e' maggiore o uguale al prezzo piu' alto di offerta
                    //matching fallito
                    break;
                }
                //prendo la lista di vendita abilitata di bestBidPrice
                ConcurrentLinkedQueue<Order> list = bestBidEntry.getValue();
                
                //controlla se ci sono gli ordini al bestBidPrice
                if(list.isEmpty()){
                    bidBook.remove(bestBidPrice);
                    continue; //nel caso affermativo, passa al prossimo prezzo
                }
                
                //visto che non e' vuoto, prendi il primo della lista
                Order topBid = list.peek();
                
                //calcola tradeSize, ovvero la size di ordine da matchare
                int tradeSize = Math.min(incoming.getRemainingSize(),topBid.getRemainingSize());
                executeTrade(topBid,incoming,bestBidPrice,tradeSize);
               
                //se il volume dell'ordine e' vuota allora elimina l'ordine dalla lista
                if(topBid.getRemainingSize() == 0){
                    list.poll();
                    if(list.isEmpty()){
                        bidBook.remove(bestBidEntry.getKey());
                    }
                }
            }
        } else {
            //caso bid, logica uguale alla precedente
            while(incoming.getRemainingSize() > 0) {
                Map.Entry<Integer, ConcurrentLinkedQueue<Order>> bestAskEntry = askBook.firstEntry();
                if (bestAskEntry == null) break;
                int bestAskPrice = bestAskEntry.getKey();
                ConcurrentLinkedQueue<Order> list = bestAskEntry.getValue();
                if(list.isEmpty()){
                    askBook.remove(bestAskEntry.getKey());
                    continue;
                }
                Order topAsk = list.peek();
                int tradeSize = Math.min(incoming.getRemainingSize(),topAsk.getRemainingSize());
                executeTrade(incoming,topAsk,bestAskPrice,tradeSize);
                
                
                if(topAsk.getRemainingSize() == 0){
                    list.poll();
                    if(list.isEmpty()){
                        askBook.remove(bestAskEntry.getKey());
                    }
                }
            }
        }
    }
    
    /*
     matching di market order, price = 0
    * selling: type = ask => contro i bid
    * buying: type = bid => contro gli ask
    * */
    private void matchMarketOrder(Order incoming){
        //lock gia' acquisita dal chiamante (writelock)
        if(incoming.getType().equals("ask")){
            while(incoming.getRemainingSize() > 0) {
                Map.Entry<Integer, ConcurrentLinkedQueue<Order>> bestBidEntry = bidBook.firstEntry();
                if (bestBidEntry == null) {
                    System.out.println("[CrossServer - matchMarket] No bids available to match the market ask order");
                    break;
                }
                
                ConcurrentLinkedQueue<Order> list = bestBidEntry.getValue();
                if(list.isEmpty()){
                    bidBook.remove(bestBidEntry.getKey());
                    continue;
                }
                
                Order topBid = list.peek();
                int tradeSize = Math.min(incoming.getRemainingSize(),topBid.getRemainingSize());
                System.out.println("Matching market ask order with bid price: " + bestBidEntry.getKey() + " | Trade size: " + tradeSize);
                executeTrade(topBid,incoming,bestBidEntry.getKey(),tradeSize);
                
                if(topBid.getRemainingSize() == 0){
                    list.poll();
                    if(list.isEmpty()){
                        bidBook.remove(bestBidEntry.getKey());
                    }
                }
            }
        } else {
            //type bid
            while(incoming.getRemainingSize() > 0){
                Map.Entry<Integer, ConcurrentLinkedQueue<Order>> bestAskEntry = askBook.firstEntry();
                if (bestAskEntry == null) {
                    System.out.println("[CrossServer - matchMarket] No bids available to match the market bid order");
                    break;
                }
                ConcurrentLinkedQueue<Order> list = bestAskEntry.getValue();
                if(list.isEmpty()){
                    askBook.remove(bestAskEntry.getKey());
                    continue;
                }
                
                Order topAsk = list.peek();
                int tradeSize = Math.min(incoming.getRemainingSize(),topAsk.getRemainingSize());
                System.out.println("Matching market bid order with ask price: " + bestAskEntry.getKey() + " | Trade size: " + tradeSize);
                executeTrade(incoming,topAsk,bestAskEntry.getKey(),tradeSize);
                
                if(topAsk.getRemainingSize() == 0){
                    list.poll();
                    if(list.isEmpty()){
                        askBook.remove(bestAskEntry.getKey());
                    }
                }
                
            }
        }
    }
    
    //ogni volta che cambia lastPrice, controlliamo se scatta qualche stopOrder
    private void checkStopOrders(){
        rwLock.writeLock().lock();
        try{
            Iterator<Order> iterator = stopOrders.iterator();
            while(iterator.hasNext()){
                Order order = iterator.next();
                try{
                if(order.getType().equals("ask")){
                    System.out.println("Activating Stop Ask Order ID: " + order.getOrderId() + " | Size: " + order.getRemainingSize() + " | Stop Price: " + order.getPrice());
                    //lastPrice <= stopPrice
                    if(lastPrice <= order.getPrice()){
                        //converti in market, price = 0 va bene perche' il prezzo sara' quello del mercato
                        Order market = new Order(order.getOrderId(), "ask", order.getRemainingSize(), 0, "market", order.getOwner());
                        iterator.remove();
                        matchMarketOrder(market);
                    }
                } else {
                    //type: bid, scatta se lastPrice >= stopPrice
                    if(lastPrice >= order.getPrice()){
                        System.out.println("Activating Stop Bid Order ID: " + order.getOrderId() + " | Size: " + order.getRemainingSize() + " | Stop Price: " + order.getPrice());
                        Order market = new Order(order.getOrderId(), "bid", order.getRemainingSize(), 0, "market", order.getOwner());
                        iterator.remove();
                        matchMarketOrder(market);
                    }
                }
                } catch (Exception e) {
                    System.err.println("[CrossServer - checkStop] checkStopOrders: Exception while checking stop orders - " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    
    //esecuzione trade di dimensione tradeSize al prezzo price, tra un orderAsk e un orderBid
    private void executeTrade(Order bidSide, Order askSide, int price, int tradeSize){
        //gia' protetto dal write lock del chiamante (matchLimitOrder/matchMarketOrder)
        //assumiamo che bidSide sia sempre "bid" e askSide sia sempre "ask", garantita dal matching
        try{
        //esecuzione fill
        bidSide.fill(tradeSize);
        askSide.fill(tradeSize);
        
        //lastprice
        lastPrice= price;
        System.out.println("Trade executed: Bid ID=" + bidSide.getOrderId() + " | Ask ID=" + askSide.getOrderId() + " | Price=" + price + " | Size=" + tradeSize);
        
        //stopcheck
        checkStopOrders();
        
        
        long timeStamp = System.currentTimeMillis();
        
        //creazione oggetto trade lato bid
        Trade bidTrade = new Trade(
                bidSide.getOrderId(),  // orderId
                "bid",                 // type
                bidSide.getOrderType(),// orderType
                tradeSize,             // size
                price,                 // price
                timeStamp);            //timestamp
        
        //creazione oggetto trade lato ask
        Trade askTrade = new Trade(
                askSide.getOrderId(),  // orderId
                "ask",                 // type
                askSide.getOrderType(),// orderType
                tradeSize,             // size
                price,                 // price
                timeStamp);            //timestamp
        
        //salavtaggio ordine nello salavataggio
        rwLock.writeLock().lock();
        try{
            executedTrades.add(bidTrade);
            executedTrades.add(askTrade);
        } finally {
            rwLock.writeLock().unlock();
        }
        
        //invio notifica via UDP
        notifyTradeExecution(bidSide,askSide,bidTrade);
        notifyTradeExecution(bidSide,askSide,askTrade);
        } catch (Exception e){
            System.err.println("[CrossServer - executeTrade] Exception during trade execution - " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    
    public void notifyTradeExecution(Order bid, Order ask, Trade trade) {
        //recuperiamo owner
        String user = trade.getType().equals("bid") ? bid.getOwner() : ask.getOwner();
        
        ClientNotificationInfo info = clientNotifications.get(user);
        if(info != null){
            notificationService.sendNotificiation(info,buildTradeNotificationJson(trade, trade.getType().toUpperCase()));
        }
        
    }
    
    private String buildTradeNotificationJson(Trade trade, String type) {
        Map<String,Object> tradeObj = new HashMap<>();
        tradeObj.put("orderId",trade.getOrderId());
        tradeObj.put("type", type.toLowerCase());
        tradeObj.put("orderType", trade.getOrderType());
        tradeObj.put("size", trade.getSize());
        tradeObj.put("price", trade.getPrice());
        tradeObj.put("timestamp", trade.getTimestamp());
        
        Map<String,Object> root = new HashMap<>();
        root.put("trades", Collections.singletonList(tradeObj));
        return gson.toJson(root);
    }
    
    //aggiunge un limitOrder non completamente evaso al book corrispondente
    private void addToBook(Order order){
        //protetto da writeLock del chiamante
        if(order.getType().equals("ask")){
            askBook.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).add(order);
        } else {
            bidBook.computeIfAbsent(order.getPrice(), k -> new ConcurrentLinkedQueue<>()).add(order);
        }
    }
    
    //rimuove un ordine dal book, se presente e non completamente eseguito
    private boolean removeFromBook(int OrderId, ConcurrentSkipListMap<Integer, ConcurrentLinkedQueue<Order>> book){
        //lock gia' acquisito
        for(Map.Entry<Integer,ConcurrentLinkedQueue<Order>> entry: book.entrySet()){
            ConcurrentLinkedQueue<Order> list = entry.getValue();
            //rimuoviamo se troviamo l'ordine
            Iterator<Order> iterator = list.iterator();
            while(iterator.hasNext()){
                Order order = iterator.next();
                if(order.getOrderId() == OrderId && order.getRemainingSize()>0){
                    iterator.remove();
                    if(list.isEmpty()){
                        book.remove(order.getPrice());
                    }
                    return true;
                }
            }
        
        }
        return false;
    }
    

    private boolean removeFromStopOrders(int orderId){
        //lock gia' acquisto
            Iterator<Order> iterator = stopOrders.iterator();
            while(iterator.hasNext()){
                Order order = iterator.next();
                if(order.getOrderId() == orderId && order.getRemainingSize()>0){
                    iterator.remove();
                    return true;
                }
            }
        return false;
    }
    
    public Gson getGson() {
        return gson;
    }
    
    
    public static class ClientNotificationInfo{
        public final String ip;
        public final int port;
        public ClientNotificationInfo(String ip, int port){
            this.ip = ip;
            this.port = port;
        }
    }
    
    public void registerNotification(String username, String ip, int port){
        clientNotifications.put(username, new ClientNotificationInfo(ip, port));
    }
    
    
    
    public Map<String,OHLC> getPriceHistory(String monthYear){
        // formato messaggio "MMYYYY"
        // estrazione i primi 2 caratteri come mese, i successivi 4 come anno
        if(monthYear.length() != 6){
            //se non rispetta la lunghezza 6
            //error gestione
            return Collections.emptyMap();
        }
        
        String mmString = monthYear.substring(0,2);
        String yyyyString = monthYear.substring(2);
        
        int year;
        int month;
        try{
            month = Integer.parseInt(mmString);
            year = Integer.parseInt(yyyyString);
        } catch (NumberFormatException e){
            return Collections.emptyMap();
        }
        
        if (month <1 || month >12){
            System.out.println("Invalid month value: "+ month);
            return Collections.emptyMap();
        }
        Map<String,OHLC> dailyMap = new TreeMap<>();
        
        synchronized (executedTrades){
            for (Trade t: executedTrades){
                Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                cal.setTimeInMillis(t.getTimestamp());
                
                int y = cal.get(Calendar.YEAR);
                int m = cal.get(Calendar.MONTH) + 1;  // inizia da 0
                int d = cal.get(Calendar.DAY_OF_MONTH);
                
                if(y == year && m == month){
                    //formattiamo YYYY-MM-DD
                    String dayString = String.format("%04d-%02d-%02d",y,m,d);
                    OHLC ohlc = dailyMap.getOrDefault(dayString,new OHLC());
                    ohlc.update(t.getPrice());
                    dailyMap.put(dayString,ohlc);
                    System.out.println("Processed trade for day: " + dayString + " | Price: " + t.getPrice());
                }
            }
        }
        return dailyMap;
    }
    
    /**
     * Classe di supporto per daily OHLC
     */
    public static class OHLC {
        public int open = -1;
        public int high = -1;
        public int low = -1;
        public int close = -1;
        
        public void update(int price) {
            if (open < 0) {
                open = price;
                close = price;
                high = price;
                low = price;
            } else {
                if (price > high) high = price;
                if (price < low) low = price;
                close = price;
            }
        }
    }
    
}



