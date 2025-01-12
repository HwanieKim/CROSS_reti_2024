package cross.utils;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import cross.server.Trade;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;

public class JsonPersistence {

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    //carica utenti (username -> password) da un file JSON
    public static void loadUsers(String filename, Map<String,String> userMap){
        File file = new File(filename);
        if(!file.exists()){
            System.out.println("[JsonPersistence - loadUsers] File does not exist");
            return;
        }
        try(Reader reader = new FileReader(file)){
            Type type =  new TypeToken<Map<String, Map<String,String>>>() {}.getType();
            Map<String,Map<String,String>> root = gson.fromJson(reader, type);
            if(root == null || !root.containsKey("users")){
                System.out.println("[JsonPersistence - loadUsers] Key 'users' not found");
                return;
            }
            
            //riempire la map con i dati caricati
            Map<String,String> loadedUsers = root.get("users");
            if(loadedUsers != null){
                userMap.putAll(loadedUsers);
                System.out.println("[JsonPersistence - loadUsers] Successfully loaded "+ loadedUsers.size()+ " users");
            } else {
                System.out.println("[JsonPersistence - loadUsers] No users found ");
            }
        }catch (IOException e){
            e.printStackTrace();
        }
    }
    
    //salva utenti su file JSON, stesso formato
    
    public static void saveUsers(String filename, Map<String,String> userMap){
        Map<String, Object> root = new HashMap<>();
        root.put("users", userMap);
        
        try(Writer writer = new FileWriter(filename)){
            gson.toJson(root,writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    //carica lista di trade da file JSON
    public static List<Trade> loadTrades(String filePath){
        try(FileReader reader = new FileReader(filePath)){
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
            Type tradeListType =  new TypeToken<List<Trade>>() {}.getType();
            
            List<Trade> trades = gson.fromJson(jsonObject.getAsJsonArray("trades"),tradeListType);
            if(trades == null){
                trades = new ArrayList<>();
            }
            return trades;
        }catch (FileNotFoundException e){
            System.err.println("[JsonPersistence - loadTrades] Trades file not found: " + filePath+ ". Starting with empty trade list");
            return new ArrayList<>();
        } catch (IOException e) {
            System.err.println("[JsonPersistence - loadTrades] Error loading trades from: " + filePath+ ":" + e.getMessage());
            return new ArrayList<>();
        }
    }
    //salvataggio di lista di trade su file JSON
    public static void saveTrades(String filename, List<Trade> trades){
        Map<String, Object> root = new HashMap<>();
        List<Map<String,Object>> tradesList = new ArrayList<>();
        
        for(Trade trade : trades){
            Map<String,Object> tradeMap = new HashMap<>();
            tradeMap.put("orderId", trade.getOrderId());
            tradeMap.put("type", trade.getType());
            tradeMap.put("orderType", trade.getOrderType());
            tradeMap.put("size", trade.getSize());
            tradeMap.put("price", trade.getPrice());
            tradeMap.put("timestamp", trade.getTimestamp());
            tradesList.add(tradeMap);
        }
            root.put("trades", trades);
        
        try (Writer writer = new FileWriter(filename)){
            gson.toJson(root,writer);
            System.out.println("[JsonPersistence - saveTrades] successfully saved in "+ filename);
        } catch (IOException e) {
            System.out.println("[JsonPersistence - saveTrades] error saving " + filename);
            e.printStackTrace();
        }
    }
}
