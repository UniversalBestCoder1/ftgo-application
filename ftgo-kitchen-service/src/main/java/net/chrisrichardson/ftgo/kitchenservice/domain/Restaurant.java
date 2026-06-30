package net.chrisrichardson.ftgo.kitchenservice.domain;

import io.eventuate.tram.events.common.DomainEvent;
import net.chrisrichardson.ftgo.kitchenservice.api.TicketDetails;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;

@Entity
@Table(name = "kitchen_service_restaurants")
@Access(AccessType.FIELD)
public class Restaurant {

  @Id
  private Long id;

  @Embedded
  @ElementCollection
  @CollectionTable(name = "kitchen_service_restaurant_menu_items")
  private List<MenuItem> menuItems;

  private Restaurant() {
  }

  public Restaurant(long id, List<MenuItem> menuItems) {
    this.id = id;
    this.menuItems = menuItems;
  }

  public List<DomainEvent> reviseMenu(RestaurantMenu revisedMenu) {
    this.menuItems = revisedMenu.getMenuItems();
    return java.util.Collections.emptyList();
  }

  public void verifyRestaurantDetails(TicketDetails ticketDetails) {
    if (ticketDetails.getLineItems() == null) return;
    ticketDetails.getLineItems().forEach(lineItem -> {
      if (menuItems.stream().noneMatch(mi -> mi.getId().equals(lineItem.getMenuItemId()))) {
        throw new IllegalArgumentException("Unknown menu item: " + lineItem.getMenuItemId());
      }
    });
  }

  public Long getId() {
    return id;
  }

}
