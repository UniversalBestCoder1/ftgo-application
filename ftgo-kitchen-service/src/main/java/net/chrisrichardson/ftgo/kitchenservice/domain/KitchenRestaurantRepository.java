package net.chrisrichardson.ftgo.kitchenservice.domain;

import org.springframework.data.repository.CrudRepository;

// Renamed from RestaurantRepository to avoid Spring Data JPA bean-name conflict
// when sharing an ApplicationContext with OrderService (both had 'restaurantRepository' bean).
public interface KitchenRestaurantRepository extends CrudRepository<Restaurant, Long> {
}
