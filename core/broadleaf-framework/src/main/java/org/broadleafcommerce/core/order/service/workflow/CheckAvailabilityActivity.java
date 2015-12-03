/*
 * #%L
 * BroadleafCommerce Framework
 * %%
 * Copyright (C) 2009 - 2014 Broadleaf Commerce
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.broadleafcommerce.core.order.service.workflow;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.broadleafcommerce.core.catalog.domain.Product;
import org.broadleafcommerce.core.catalog.domain.ProductBundle;
import org.broadleafcommerce.core.catalog.domain.Sku;
import org.broadleafcommerce.core.catalog.domain.SkuBundleItem;
import org.broadleafcommerce.core.catalog.domain.SkuBundleItemImpl;
import org.broadleafcommerce.core.catalog.service.CatalogService;
import org.broadleafcommerce.core.inventory.service.ContextualInventoryService;
import org.broadleafcommerce.core.inventory.service.InventoryUnavailableException;
import org.broadleafcommerce.core.inventory.service.type.InventoryType;
import org.broadleafcommerce.core.order.domain.BundleOrderItem;
import org.broadleafcommerce.core.order.domain.DiscreteOrderItem;
import org.broadleafcommerce.core.order.domain.OrderItem;
import org.broadleafcommerce.core.order.service.OrderItemService;
import org.broadleafcommerce.core.order.service.call.OrderItemRequestDTO;
import org.broadleafcommerce.core.workflow.BaseActivity;
import org.broadleafcommerce.core.workflow.ProcessContext;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

/**
 * This activity handles both adds and updates. In both cases, this will check the availability and quantities (if applicable)
 * of the passed in request. If this is an update request, this will use the {@link Sku} from {@link OrderItemRequestDTO#getOrderItemId()}.
 * If this is an add request, there is no order item yet so the {@link Sku} is looked up via the {@link OrderItemRequestDTO#getSkuId()}.
 * 
 * @author Phillip Verheyden (phillipuniverse)
 */
public class CheckAvailabilityActivity extends BaseActivity<ProcessContext<CartOperationRequest>> {

    private static final Log LOG = LogFactory.getLog(CheckAvailabilityActivity.class);
    
    @Resource(name = "blCatalogService")
    protected CatalogService catalogService;
    
    @Resource(name = "blOrderItemService")
    protected OrderItemService orderItemService;
    
    @Resource(name = "blInventoryService")
    protected ContextualInventoryService inventoryService;

    @Value("${defaultCheckBundleItemFlag:false}")
    protected boolean defaultCheckBundleItemFlag;
    
    @Override
    public ProcessContext<CartOperationRequest> execute(ProcessContext<CartOperationRequest> context) throws Exception {
        CartOperationRequest request = context.getSeedData();
        
        Sku sku;
        Long orderItemId = request.getItemRequest().getOrderItemId();
        OrderItem orderItem = null;
        boolean isBundle = false;
        ProductBundle productBundle = null;
        if (orderItemId != null) {
            // this must be an update request as there is an order item ID available
            orderItem = orderItemService.readOrderItemById(orderItemId);
            if (orderItem instanceof DiscreteOrderItem) {
                sku = ((DiscreteOrderItem) orderItem).getSku();
            } else if (orderItem instanceof BundleOrderItem) {
                sku = ((BundleOrderItem) orderItem).getSku();
                productBundle = ((BundleOrderItem) orderItem).getProductBundle();
                isBundle = true;
            } else {
                LOG.warn("Could not check availability; did not recognize passed-in item " + orderItem.getClass().getName());
                return context;
            }
        } else {
            // No order item, this must be a new item add request
            Long skuId = request.getItemRequest().getSkuId();
            sku = catalogService.findSkuById(skuId);
            Product product = sku.getDefaultProduct();
            if(product instanceof ProductBundle){
                isBundle = true;
                productBundle = (ProductBundle) product;
            }
        }
        
        
        // First check if this Sku is available
        if (!sku.isAvailable()) {
            throw new InventoryUnavailableException("The referenced Sku " + sku.getId() + " is marked as unavailable",
                    sku.getId(), request.getItemRequest().getQuantity(), 0);
        }

        HashMap<Sku,Integer> bundleSkuQuantityMap = null;
        List<Sku> orderItemSkuList = new LinkedList<>();
        // If we are dealing with a bundle; we need to determine how the inventory quantity
        // is done.
        if(isBundle){
            bundleSkuQuantityMap = new LinkedHashMap<>();
            if(shouldCheckBundleItemInventory(productBundle)){
                List<SkuBundleItem> skuBundleItems = productBundle.getSkuBundleItems();
                for(SkuBundleItem skuBundleItem : skuBundleItems){
                    Sku itemSku = skuBundleItem.getSku();
                    orderItemSkuList.add(itemSku);
                    if(bundleSkuQuantityMap.containsKey(sku)){
                        Integer quantity = bundleSkuQuantityMap.get(sku);
                        quantity = quantity + skuBundleItem.getQuantity();
                        bundleSkuQuantityMap.put(sku,quantity);
                    }else {
                        bundleSkuQuantityMap.put(sku,skuBundleItem.getQuantity());
                    }
                }
            } else {
                // use the Quantity of ProductBundle's sku.
                orderItemSkuList.add(sku);
                // set the quantity to '1' because the sku IS the bundle
                bundleSkuQuantityMap.put(sku,1);
            }
        } else {
            orderItemSkuList.add(sku);
        }

        for(Sku orderItemSku : orderItemSkuList ){
            if (InventoryType.CHECK_QUANTITY.equals(orderItemSku.getInventoryType())) {
                Integer requestedQuantity = request.getItemRequest().getQuantity();
                if(isBundle){
                     /*
                        It is possible that a bundle contains a quantity of 2+ for a SkuBundleItem.
                        in that case we want to multiply the requested quantity bundle times the
                        quantity of items it takes to make the bundle.
                    */
                    Integer bundleCompositionQuantity = bundleSkuQuantityMap.get(sku);
                    requestedQuantity = requestedQuantity * bundleCompositionQuantity;
                }

                Map<String, Object> inventoryContext = new HashMap<String, Object>();
                inventoryContext.put(ContextualInventoryService.ORDER_KEY, context.getSeedData().getOrder());
                boolean available = inventoryService.isAvailable(orderItemSku, requestedQuantity, inventoryContext);
                if (!available) {
                    throw new InventoryUnavailableException(orderItemSku.getId(),
                            requestedQuantity, inventoryService.retrieveQuantityAvailable(orderItemSku, inventoryContext));
                }
            }
        }

        
        // the other case here is ALWAYS_AVAILABLE and null, which we are treating as being available
        
        return context;
    }

    /**
     *
     * @param productBundle
     * @return whether or not the Inventory of the bundle's items.
     */
    protected boolean shouldCheckBundleItemInventory(ProductBundle productBundle) {
        boolean result = defaultCheckBundleItemFlag;
        if(productBundle.getUseItemInventory() != null){
            result = productBundle.getUseItemInventory();
        }
        return result;
    }

}
