package jpabook.jpashop.api;


import jpabook.jpashop.domain.Address;
import jpabook.jpashop.domain.Order;
import jpabook.jpashop.domain.OrderStatus;
import jpabook.jpashop.repository.OrderRepository;
import jpabook.jpashop.repository.OrderSearch;
import jpabook.jpashop.repository.order.simplequery.OrderSimpleQueryDto;
import jpabook.jpashop.repository.order.simplequery.OrderSimpleQueryRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * XToOne (ManyToOne, OneToOne) : 투원 관계에서의 성능 최적화 , 단순
 * Order
 * Order -> Member
 * Order -> Delivery
 */

@RestController
@RequiredArgsConstructor
public class OrderSimpleController {
    private final OrderSimpleQueryRepository orderSimpleQueryRepository;
    private final OrderRepository orderRepository;

    /**
     *  문제점이 많다.
     *  1. 이렇게 하면 order가 member를 조회하고 조회된 member에는 order가 있어서 조회하고 무한루프에 빠짐.
     *    - 엔티티를 직접 노출할 때 양방향 연관관계과 걸려 있으면 한쪽에는 @JsonIgnore를 해줘야한다.
     *  2. proxy.pojo.bytebuddy.ByteBuddyInterceptor 관련 에러 발생. ToOne관계에서 (fetch = LAZY)를 설정해두었기 때문에
     *     jpa는 실제 db에서 관련 정보를 가져오는게 아니라 프록시 객체를 만들어서 조회를 진행한다. 그러다가 실제로 해당 실제 객체 데이터 값에 손을 대면 그제서야 db에 sql을 날려서 값을 채워준다.
     *    - 이런경우 하이버네이트 모듈을 빈으로 등록해서 해결은 가능.
     *
     *   애초에 DTO 객체 쓰면 이런 문제를 해결 가능하다. 그리고 엔티티를 노출하면 나중에 엔티티정보 바뀌면 api스펙이 다 바뀐다.
     */

    @GetMapping("/api/v1/simple-orders")
    public List<Order> ordersV1() {
        List<Order> all = orderRepository.findAllByString(new OrderSearch());
        for(Order order : all) {
            order.getMember().getName(); // Lazy 강제 초기화
            order.getDelivery().getAddress(); // Lazy 강제 초기화
        }
        return all;
    }


    /**
     * V2. 엔티티를 조회해서 DTO로 변환(fetch join 사용X)
     * - 단점: 지연로딩으로 쿼리 N번 호출 (N + 1)
     */
    @GetMapping("/api/v2/simple-orders")
    public List<SimpleOrderDto> orderV2(){
        // SQL 1번 ORDER 2개
        List<Order> orders = orderRepository.findAllByString(new OrderSearch());

        // 각각의 ORDER에 대한 Member.getName() , Delivery().getAdrress()를 위해서 SQL 이 각각 1번씩 나감.
        // 그래서 총 5번의 SQL이 실행됨. 성능이 매우 안좋다.
        // N + 1 문제 --> 주문 1 + 회원 N + 배송 N --> N=2이므로 최악의 경우 총 5번 SQL 이 실행된다.
        // 기본적으로 LAZY는 영속성컨텍스트를 먼저찔러서 데이터를 확인하는데 회원정보가 만약에 같은 회원이라면 회원 SQL은 처음 1번만 날라가서 총 4번 일 것임.
        List<SimpleOrderDto> result = orders.stream().map(o -> new SimpleOrderDto(o))
                .collect(Collectors.toList());
        return result;
    }

    /**
     * V3. 엔티티를 조회해서 DTO로 변환(fetch join 사용 O)
     * - fetch join으로 쿼리 1번 호출
     * 참고: fetch join에 대한 자세한 내용은 JPA 기본편 참고(정말 중요함)
     *
     * fetch join : lazy를 무시하고! 바로 디비에서 조인해서 값을 가져오는거임
     * 모든 엔티티에 LAZY를 걸고 필요한 경우에 fetch join을 이용하면됨
     */
    @GetMapping("/api/v3/simple-orders")
    public List<SimpleOrderDto> ordersV3() {
        List<Order> orders = orderRepository.findAllWithMemberDelivery();
        List<SimpleOrderDto> result = orders.stream().map(o -> new SimpleOrderDto(o))
                .collect(Collectors.toList());

        return result;
    }

    /**
     * V4. JPA에서 DTO로 바로 조회
     * 이렇게 하면 select절 최적화가 되지만 재사용성이 안좋다.
     * API스펙이 Repository 계층에 들어감
     */
    @GetMapping("/api/v4/simple-orders")
    public List<OrderSimpleQueryDto> ordersV4() {
        return orderSimpleQueryRepository.findOrderDtos();
    }


    /**
     * 쿼리 방식 선택 권장 순서
     * 1. 우선 엔티티를 DTO로 변환하는 방법을 선택.
     * 2. 필요하면 페치 조인으로 성능을 최적화 한다. -> 대부분의 성능 이슈가 해결된다.
     * 3. 그래도 안되면 DTO로 직접 조회하는 방법을 사용한다.
     * 4. 최후의 방법은 JPA가 제공하는 네이티브 SQL이나 스프링 JDBC Templcate을 사용해서 SQL을 직접 사용한다.
     */


    @Data
    static class SimpleOrderDto {
        private Long orderId;
        private String name;
        private LocalDateTime orderDate;
        private OrderStatus orderStatus;
        private Address address;

        public SimpleOrderDto(Order order) {
            orderId = order.getId();
            name = order.getMember().getName(); // Lazy 초기화 : 첨엔 Lazy라서 프록시객체인데 getName() 은 영속성컨텍스트에 없으니까 db에서 sql날려서 찾아온다.
            orderDate = order.getOrderDate();
            orderStatus = order.getStatus();
            address = order.getDelivery().getAddress(); // Lazy 초기화
        }
    }





}
