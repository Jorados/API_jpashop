package jpabook.jpashop.api;


import jpabook.jpashop.domain.Address;
import jpabook.jpashop.domain.Order;
import jpabook.jpashop.domain.OrderItem;
import jpabook.jpashop.domain.OrderStatus;
import jpabook.jpashop.repository.OrderRepository;
import jpabook.jpashop.repository.OrderSearch;
import jpabook.jpashop.repository.order.query.OrderFlatDto;
import jpabook.jpashop.repository.order.query.OrderItemQueryDto;
import jpabook.jpashop.repository.order.query.OrderQueryDto;
import jpabook.jpashop.repository.order.query.OrderQueryRepository;
import jpabook.jpashop.repository.order.simplequery.OrderSimpleQueryDto;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.jaxb.SpringDataJaxb;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

import static java.util.stream.Collectors.*;

@RestController
@RequiredArgsConstructor
public class OrderApiController {
    private final OrderRepository orderRepository;
    private final OrderQueryRepository orderQueryRepository;

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


    /**
     * V3.1 엔티티를 조회해서 DTO로 변환 페이징 고려
     * - ToOne 관계만 우선 모두 페치 조인으로 최적화
     * - 컬렉션 관계는 hibernate.default_batch_fetch_size, @BatchSize로 최적화 ---> 1+N+M 쿼리를 1+1+1 로 만들어준다.
     */

    // ToMany 관계 컬렉션과 페이징을 한번에 하려면 ??
    // 1. ToOne 관계 모두 우선적으로 fetch join (계속 ToOne의 ToOne의 ToOne이라면 계속 fetch join 가능)
    // 2. 컬렉션은 지연 로딩으로 조회한다.
    // 3. 지연 로딩 성능최적화를 위해 hibernate.default_batch_fetch_size(글로벌), @BatchSize(개별) 를 적용한다.
    // -> 이 설정은 기존에 따로따로 날아가야하는 1+N 쿼리에 대해서 IN 쿼리를 추가해서 설정 값 크기만큼 한번에 보내준다. 1+N에 대한 최적화가 어느정도 된거임 + 페이징도 가능해짐.
    // -> v3 보다 DB에서 뽑아오는 데이터 전송량이 최적화됨.
    // -> !! 컬렉션 fetch join은 페이징이 불가능하지만 이 방법은 페이징이 가능하다.
    // -> 결론 : hibernate.default_batch_fetch_size 를 디폴트로 설정하자. (최소는 ( 보통 100개 )노상관 , 최대는 1000개 -> IN 쿼리 1000개 넘으면 오류뜨는 DB가 있음 )
    //          이 값을 줄이면 총 시간이 증가하고, 이 값을 늘리면 DB에서 애플리케이션으로 데이터를 한번에 쫙보낼때 DB 부하가 생긴다. DB랑 WAS에서 부하가 견뎌지는 만큼 하면된다.

    @GetMapping("/api/v3.1/orders")
    public List<OrderDto> ordersV3_page(@RequestParam(value = "offset", defaultValue = "0") int offset,
                                        @RequestParam(value = "limit", defaultValue = "100") int limit) {

        List<Order> orders = orderRepository.findAllWithMemberDelivery(offset, limit);
        List<OrderDto> result = orders.stream()
                .map(o -> new OrderDto(o))
                .collect(toList());

        return result;
    }

    // 이건 그냥 귀찮음
    @GetMapping("/api/v4/orders")
    public List<OrderQueryDto> ordersV4() {
        return orderQueryRepository.findOrderQueryDtos();
    }

    // 어려움
    // Order를 기준으로 본다면
    @GetMapping("/api/v5/orders")
    public List<OrderQueryDto> ordersV5() {
        return orderQueryRepository.findAllByDto_optimization();
    }

    // 어렵다
    // 이거는 한방에 데이터(컬렉션때매 뻥튀기상태) 땡겨와서 로직으로 지지고 볶아서 내가 원하는 형태로 DTO에 담는거임.
    // 이렇게 하는거 아니면 ToOne 먼저 한번에 join sql날려서 DTO에 담고, 그다음 for문으로 컬렉션 1+N 쿼리날리면서 DTO에 저장하거나 , 자료구조 쓰거나 하면서 해야함.
    // 페이징 불가능 -> ToMany 조인때문에 그럼.
    @GetMapping("/api/v6/orders")
    public List<OrderQueryDto> ordersV6() {
        List<OrderFlatDto> flats = orderQueryRepository.findAllByDto_flat();

        return flats.stream()
                .collect(groupingBy(o -> new OrderQueryDto(o.getOrderId(), o.getName(), o.getOrderDate(), o.getOrderStatus(), o.getAddress()),
                        mapping(o -> new OrderItemQueryDto(o.getOrderId(), o.getItemName(), o.getOrderPrice(), o.getCount()), toList())
                )).entrySet().stream()
                .map(e -> new OrderQueryDto(e.getKey().getOrderId(), e.getKey().getName(), e.getKey().getOrderDate(), e.getKey().getOrderStatus(), e.getKey().getAddress(), e.getValue()))
                .collect(toList());
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
