package cross.server;

/*
* registra un singolo trade eseguito:
* - price
* - size
* - timestamp
* - bidOrderId
* - askOrderId
* */
public class Trade {
    private final int orderId;
    private final String type; // bid or ask
    private final int price;
    private final int size;
    private final long timestamp;
    private final String orderType;
    
    
    public Trade(int orderId, String type, String orderType, int size, int price,long timestamp) {
        this.orderId = orderId;
        this.type = type.toLowerCase();
        this.orderType = orderType;
        this.price = price;
        this.size = size;
        this.timestamp = timestamp;
        
    }
    public int getOrderId() {
        return orderId;
    }
    public String getType() {
        return type;
    }
    public int getPrice() {
        return price;
    }
    
    public int getSize() {
        return size;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
  
    public String getOrderType() {
        return orderType;
    }
}
