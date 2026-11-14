package com.example.shop.warehouse.model;

import jakarta.persistence.*;

@Entity
@Table(name = "stock")
public class Stock {
    @Id
    @Column(name = "sku", length = 64)
    private String sku;

    @Column(name = "qty_available", nullable = false)
    private int qtyAvailable;

    @Column(name = "qty_reserved", nullable = false)
    private int qtyReserved;

    public Stock() {}
    public Stock(String sku, int qtyAvailable) { this.sku = sku; this.qtyAvailable = qtyAvailable; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public int getQtyAvailable() { return qtyAvailable; }
    public void setQtyAvailable(int qtyAvailable) { this.qtyAvailable = qtyAvailable; }
    public int getQtyReserved() { return qtyReserved; }
    public void setQtyReserved(int qtyReserved) { this.qtyReserved = qtyReserved; }
}
