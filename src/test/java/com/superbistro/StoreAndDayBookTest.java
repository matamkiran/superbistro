package com.superbistro;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Store room (have / buy / bring in) and the daily cash book. */
@SpringBootTest
class StoreAndDayBookTest {

    @Autowired
    WebApplicationContext ctx;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    private MockHttpSession login(String path, String body) throws Exception {
        var res = mvc.perform(post(path).session(new MockHttpSession()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private static String idOf(String json, String name) {
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\",\"name\":\"" + Pattern.quote(name) + "\"").matcher(json);
        if (!m.find()) throw new AssertionError(name + " not in " + json);
        return m.group(1);
    }

    private String json(MockHttpSession s, org.springframework.test.web.servlet.RequestBuilder rb) throws Exception {
        return mvc.perform(rb).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void storeManagerDay() throws Exception {
        MockHttpSession s = login("/api/auth/signup",
            "{\"restaurantName\":\"Store Test\",\"name\":\"Owner\",\"email\":\"store-owner@example.com\",\"password\":\"secret123\"}");

        // What I have: rice is below its minimum, oil is fine.
        mvc.perform(post("/api/stock").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Basmati Rice\",\"unit\":\"kg\",\"category\":\"Grains\",\"currentQty\":4,\"minQty\":10,\"reorderQty\":20,\"lastCost\":60}"))
            .andExpect(status().isOk());
        String items = json(s, post("/api/stock").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Sunflower Oil\",\"unit\":\"L\",\"category\":\"Oil\",\"currentQty\":8,\"minQty\":5}"));
        String rice = idOf(items, "Basmati Rice");
        mvc.perform(get("/api/stock").session(s))
            .andExpect(jsonPath("$[?(@.name == 'Basmati Rice')].status").value("LOW"))
            .andExpect(jsonPath("$[?(@.name == 'Basmati Rice')].suggestQty").value(20))
            .andExpect(jsonPath("$[?(@.name == 'Sunflower Oil')].status").value("OK"));

        // What I need to bring in: order 20 kg.
        String order = json(s, post("/api/purchases").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"supplier\":\"Ravi Traders\",\"lines\":[{\"stockItemId\":\"" + rice + "\",\"qty\":20}]}"));
        String orderId = order.substring(order.indexOf("\"id\":\"") + 6, order.indexOf("\"id\":\"") + 42);
        mvc.perform(get("/api/stock").session(s)).andExpect(jsonPath("$[?(@.name == 'Basmati Rice')].onOrderQty").value(20));

        // Goods arrive: 18 kg at 62/kg, paid cash -> stock 22, day book shows 1116 paid out.
        mvc.perform(post("/api/purchases/" + orderId + "/receive").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment\":\"CASH\",\"lines\":[{\"stockItemId\":\"" + rice + "\",\"qty\":18,\"unitCost\":62}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("RECEIVED")))
            .andExpect(jsonPath("$.total", closeTo(1116.0, 0.001)));
        mvc.perform(get("/api/stock").session(s))
            .andExpect(jsonPath("$[?(@.name == 'Basmati Rice')].currentQty").value(22))
            .andExpect(jsonPath("$[?(@.name == 'Basmati Rice')].status").value("OK"))
            .andExpect(jsonPath("$[?(@.name == 'Basmati Rice')].onOrderQty").value(0));

        // Kitchen used 2 kg; count says 19 (1 kg unaccounted); can't use more than there is.
        mvc.perform(post("/api/stock/" + rice + "/move").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"USE\",\"qty\":2}")).andExpect(jsonPath("$[?(@.name == 'Basmati Rice')].currentQty").value(20));
        mvc.perform(post("/api/stock/" + rice + "/move").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"COUNT\",\"qty\":19}")).andExpect(jsonPath("$[?(@.name == 'Basmati Rice')].currentQty").value(19));
        mvc.perform(post("/api/stock/" + rice + "/move").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"USE\",\"qty\":50}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stock/" + rice + "/moves").session(s)).andExpect(jsonPath("$", hasSize(4)));

        // A cash sale of 2 x 100 (+5% GST = 210) and a UPI sale of 100 (105).
        String menu = json(s, post("/api/menu/import").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":[{\"name\":\"Veg Meals\",\"price\":100,\"category\":\"Meals\"}]}"));
        String dish = idOf(menu, "Veg Meals");
        mvc.perform(post("/api/orders").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"OFFLINE\",\"payment\":\"CASH\",\"items\":[{\"menuItemId\":\"" + dish + "\",\"qty\":2}]}")).andExpect(status().isOk());
        mvc.perform(post("/api/orders").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"OFFLINE\",\"payment\":\"UPI\",\"items\":[{\"menuItemId\":\"" + dish + "\",\"qty\":1}]}")).andExpect(status().isOk());

        // Gas bill paid in cash, owner added 1000 cash to the drawer.
        mvc.perform(post("/api/daybook/entries").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"direction\":\"OUT\",\"method\":\"CASH\",\"category\":\"Gas\",\"amount\":900,\"note\":\"Cylinder\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/daybook/entries").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"direction\":\"IN\",\"method\":\"CASH\",\"category\":\"Cash added\",\"amount\":1000}")).andExpect(status().isOk());

        // Drawer: opening 2000 + cash sales 210 + cash in 1000 - cash out (1116 + 900) = 1194. Counted 1180 -> short 14.
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        mvc.perform(put("/api/daybook/" + today + "/drawer").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"openingCash\":2000}")).andExpect(status().isOk());
        mvc.perform(put("/api/daybook/" + today + "/drawer").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedCash\":1180}"))
            .andExpect(jsonPath("$.orders", is(2)))
            .andExpect(jsonPath("$.sales", closeTo(315.0, 0.001)))
            .andExpect(jsonPath("$.byPayment.CASH", closeTo(210.0, 0.001)))
            .andExpect(jsonPath("$.byPayment.UPI", closeTo(105.0, 0.001)))
            .andExpect(jsonPath("$.paidOut", closeTo(2016.0, 0.001)))
            .andExpect(jsonPath("$.outByCategory.Purchase", closeTo(1116.0, 0.001)))
            .andExpect(jsonPath("$.drawer.expected", closeTo(1194.0, 0.001)))
            .andExpect(jsonPath("$.drawer.difference", closeTo(-14.0, 0.001)))
            .andExpect(jsonPath("$.entries", hasSize(3)));

        mvc.perform(get("/api/daybook/summary").param("days", "7").session(s))
            .andExpect(jsonPath("$", hasSize(7)))
            .andExpect(jsonPath("$[0].day", is(today)))
            .andExpect(jsonPath("$[0].cash", closeTo(210.0, 0.001)))
            .andExpect(jsonPath("$[0].paidOut", closeTo(2016.0, 0.001)));

        // Manager can use the store and day book; staff can't.
        mvc.perform(post("/api/staff").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Mani\",\"email\":\"mani@example.com\",\"password\":\"manager123\",\"role\":\"MANAGER\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/staff").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Ravi\",\"email\":\"ravi-c@example.com\",\"password\":\"cashier123\",\"role\":\"STAFF\"}")).andExpect(status().isOk());
        MockHttpSession manager = login("/api/auth/login", "{\"email\":\"mani@example.com\",\"password\":\"manager123\"}");
        MockHttpSession staff = login("/api/auth/login", "{\"email\":\"ravi-c@example.com\",\"password\":\"cashier123\"}");
        mvc.perform(get("/api/stock").session(manager)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/daybook").session(manager)).andExpect(status().isOk());
        mvc.perform(post("/api/menu").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"X\",\"price\":1}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/stock").session(staff)).andExpect(status().isForbidden());
        mvc.perform(get("/api/daybook").session(staff)).andExpect(status().isForbidden());
    }
}
