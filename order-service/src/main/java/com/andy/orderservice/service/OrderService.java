package com.andy.orderservice.service;

import com.andy.orderservice.dto.InventoryResponse;
import com.andy.orderservice.dto.OrderLineItemsDto;
import com.andy.orderservice.dto.OrderRequest;
import com.andy.orderservice.event.OrderPlacedEvent;
import com.andy.orderservice.modelu.Order;
import com.andy.orderservice.modelu.OrderLineItems;
import com.andy.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional @Slf4j
public class OrderService {
    private String INVENTORY_CALL = "http://INVENTORY-SERVICE/api/inventory";
   private final OrderRepository orderRepository;
   private final WebClient.Builder webClientBuilder;
   private final Tracer tracer; // required because there is multiple trade as completableFuture is used and
    // because of the multithreading the tracer is not tracing all the thread. To solve that this is added.
   private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;
    public String placeOrder(OrderRequest orderRequest){
        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());

        List<OrderLineItems> orderLineItem = orderRequest.getOrderLineItemsDtoList()
                .stream()
                .map(this::mapToDto)//.map(orderLineItemsDto -> mapToDto(orderLineItemsDto))
                .toList();
        order.setOrderLineItemsList(orderLineItem);


        //Call inventory service to find out if product is available and save if it is in stock
        List<String> skuCodes = order.getOrderLineItemsList().stream()
                .map(OrderLineItems::getSkuCode)
                .toList();  //collect all the skuCode to pass as part of the request parameter using queryParam.
                            // which looks http://localhost:8082/api/inventory?skuCode=iphone-13&skuCode=iphone13-red
        log.info("Calling Inventory Service");

        Span inventoryServiceLookup = tracer.nextSpan().name("InventoryServiceLookup"); // naming the tracer span
        try(Tracer.SpanInScope spanInScope = tracer.withSpan(inventoryServiceLookup.start())){
            InventoryResponse[] inventoryResponseArray = webClientBuilder.build().get()
                    .uri(INVENTORY_CALL, uriBuilder -> uriBuilder.queryParam("skuCode", skuCodes).build())
                    .retrieve()
                    .bodyToMono(InventoryResponse[].class)
                    .block(); // to make it synchronous call
            //check if all data in the array are true
            boolean allProductsInStock = Arrays.stream(inventoryResponseArray)
                    .allMatch(InventoryResponse::isInStock);

            if(allProductsInStock){
                orderRepository.save(order);
                kafkaTemplate.send("notificationTopic", new OrderPlacedEvent(order.getOrderNumber()));
                return "Order placed successfully";
            }
            else{
                throw new IllegalArgumentException("Product is not in stock");
            }
        }finally {
            inventoryServiceLookup.end();
        }
    }

    private OrderLineItems mapToDto(OrderLineItemsDto orderLineItemsDto) {
        OrderLineItems orderLineItems = new OrderLineItems();
        orderLineItems.setPrice(orderLineItemsDto.getPrice());
        orderLineItems.setQuantity(orderLineItemsDto.getQuantity());
        orderLineItems.setSkuCode(orderLineItemsDto.getSkuCode());
        return orderLineItems;
    }
}
