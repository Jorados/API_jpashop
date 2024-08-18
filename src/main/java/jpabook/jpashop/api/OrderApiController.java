package jpabook.jpashop.api;


import jpabook.jpashop.domain.Address;
import jpabook.jpashop.domain.Order;
import jpabook.jpashop.domain.OrderItem;
import jpabook.jpashop.domain.OrderStatus;
import jpabook.jpashop.repository.OrderRepository;
import jpabook.jpashop.repository.OrderSearch;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.jaxb.SpringDataJaxb;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

import static java.util.stream.Collectors.toList;

@RestController
@RequiredArgsConstructor
public class OrderApiController {
    private final OrderRepository orderRepository;


    @GetMapping("/api/v1/orders")
    public List<Order> ordersV1(){
        List<Order> all = orderRepository.findAllByString(new OrderSearch());
        for (Order order : all) {
            order.getMember().getName(); // 초기화
            order.getDelivery().getAddress(); // 초기화

            List<OrderItem> orderItems = order.getOrderItems();// 핵심
            for (OrderItem orderItem : orderItems) {
                orderItem.getItem().getName(); // 초기화
            }
        }

        // 지금 hibernate5Module 이게 빈으로 등록되어있어서 프록시로 조회된 객체들은 무시한다.
        // 직접 초기화를 해주면 (sql을 통해 가져옴) 담는다.
        // 애초에 DTO클래스를 이용하면 이런거 신경쓰지 않아도된다.
        return all;
    }


    // DTO를 return 하더라도 DTO 안에 있는 필드값 전부다 엔티티가 존재하면안된다.
    // 100% 엔티티를 없애야한다.
    @GetMapping("/api/v2/orders")
    public List<OrderDto> ordersV2() {
        List<Order> orders = orderRepository.findAll();
        List<OrderDto> result = orders.stream()
                .map(o -> new OrderDto(o))
                .collect(toList());

        return result;
    }

    // N+1 문제 때문에 성능이 안좋은 v2를 fetch join을 이용해서 성능최적화를 해보자.
    // v2와 v3의 현재 계층 코드는 사실상 똑같다. fetch join을 썻냐 안썻냐의 차이.
    // db입장에서는 join이 되면 데이터 row를 많이 가지고 있는 쪽에 맞춰서 데이터가 select가 되기 때문에, ToOne 관계에서는 괜찮은데 ToMany에서는
    // JPA에서 값을 가져올때 ToMany 데이터 row 수에 맞는 데이터가 select 된다. 그렇기 때문에 distinct를 해주면 해결이 된다.
    // JPA에서의 distinct는 SQL에 distinct를 추가하고, 더해서 같은 엔티티가 조회되면, 애플리케이션에서 중복을 걸러준다.

    // 단점 1
    // 컬렉션 fetch join은 엄청난 단점 -> ToMany 엔티티를 fetch join을 하는 순간 --> !!!!페이징 불가능!!!!
    // 이유 : ToMany에서의 join은 디비에서 row가 많은 쪽을 기준으로 조회가된다. JPA에서 distinct를 해줬더라도 DB에서 distinct는 모든 컬럼값이 같아야 중복제거가된다.
    // 그래서 DB에서 조회되는 데이터 row는 그대로이기 때문에 JPA 측에서 페이징을 해버리게되면 DB와 JPA 쪽에서의 값이 안맞아서 페이징이 안됨.
    // 그래서 DB의 모든데이터를 가져와서 애플리케이션 메모리(JVM의 힙메모리)에서 페이징을 해버린다. ToOne에서는 상관없다.

    // 단점 2
    // 컬렉션 fetch join은 하나만 사용가능하다. 데이터가 부정합하게 조회될 수 있다.
    @GetMapping("/api/v3/orders")
    public List<OrderDto> ordersV3() {
        List<Order> orders = orderRepository.findAllWithItem();
        List<OrderDto> result = orders.stream()
                .map(o -> new OrderDto(o))
                .collect(toList());

        return result;
    }





    @Data
    static class OrderDto {

        private Long orderId;
        private String name;
        private LocalDateTime orderDate; //주문시간
        private OrderStatus orderStatus;
        private Address address;
        private List<OrderItemDto> orderItems;

        public OrderDto(Order order) {
            orderId = order.getId();
            name = order.getMember().getName();
            orderDate = order.getOrderDate();
            orderStatus = order.getStatus();
            address = order.getDelivery().getAddress();
            orderItems = order.getOrderItems().stream()
                    .map(orderItem -> new OrderItemDto(orderItem))
                    .collect(toList());
        }
    }

    @Data
    static class OrderItemDto {

        private String itemName; //상품 명
        private int orderPrice;  //주문 가격
        private int count;       //주문 수량

        public OrderItemDto(OrderItem orderItem) {
            itemName = orderItem.getItem().getName();
            orderPrice = orderItem.getOrderPrice();
            count = orderItem.getCount();
        }
    }
}
