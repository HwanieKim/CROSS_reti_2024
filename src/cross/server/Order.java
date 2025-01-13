package cross.server;

public class Order {
    private final int orderId;
    private final String type;   //ask or bid
    private final int initialsize;     //millesimi di BTC
    private final int price;   // millesimi di USD
    private final String orderType; // "limit", "stop", "market"
    private final String owner; // username dell'utente che ha creato l'ordine
    
    private int filledSize;
    
    public Order(int orderId, String type, int size, int price, String orderType, String owner) {
        this.orderId = orderId;
        this.type = type;
        this.initialsize = size;
        this.price = price;
        this.orderType = orderType;
        this.owner = owner;
        this.filledSize = 0;
    }
    
    public int getOrderId() {
        return orderId;
    }
    public String getType() {
        return type;
    }
    public int getSize() {
        return initialsize;
    }
    public int getPrice() {
        return price;
    }
    public String getOrderType() {
        return orderType;
    }
    public int getRemainingSize() {
        return initialsize - filledSize;
    }
    public void fill(int qty){
        if(qty <= 0){
            throw new IllegalArgumentException("[Order] The quantity to fill must be positive.");
        }
        if(this.filledSize + qty > this.initialsize){
            throw new IllegalArgumentException("[Order] It is impossibile to fill more than initial size of the order.");
        }
        this.filledSize += qty;
    }
    public String getOwner() {
        return owner;
    }
    
}
