package io.github.andersonchen.talkcal.schema;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 演練裡的四版「程式」（#18）：每一版就是它的 persistence adapter 送的 SQL。
 *
 * 雙寫、切讀都寫在這一層，資料庫裡沒有 trigger：換 ORM（JPA、MyBatis）時這段跟著 adapter 一起改寫，
 * 到 contract 那一版就刪掉，不會留下來越積越多。
 */
enum ContactApp {

    /** R1：只認得 phone。 */
    V1("INSERT INTO drill_contact (id, phone) VALUES (:id, :phone)",
            "UPDATE drill_contact SET phone = :phone WHERE id = :id",
            "SELECT phone FROM drill_contact WHERE id = :id"),

    /** R2：雙寫，還讀 phone。 */
    V2("INSERT INTO drill_contact (id, phone, phone_number) VALUES (:id, :phone, :phone)",
            "UPDATE drill_contact SET phone = :phone, phone_number = :phone WHERE id = :id",
            "SELECT phone FROM drill_contact WHERE id = :id"),

    /** R3：雙寫，改讀 phone_number。 */
    V3("INSERT INTO drill_contact (id, phone, phone_number) VALUES (:id, :phone, :phone)",
            "UPDATE drill_contact SET phone = :phone, phone_number = :phone WHERE id = :id",
            "SELECT phone_number FROM drill_contact WHERE id = :id"),

    /** R4 起：只寫、只讀 phone_number。 */
    V4("INSERT INTO drill_contact (id, phone_number) VALUES (:id, :phone)",
            "UPDATE drill_contact SET phone_number = :phone WHERE id = :id",
            "SELECT phone_number FROM drill_contact WHERE id = :id");

    private final String insert;
    private final String update;
    private final String select;

    ContactApp(String insert, String update, String select) {
        this.insert = insert;
        this.update = update;
        this.select = select;
    }

    void save(JdbcClient db, UUID id, String phone) {
        db.sql(insert).param("id", id).param("phone", phone).update();
    }

    void changePhone(JdbcClient db, UUID id, String phone) {
        db.sql(update).param("id", id).param("phone", phone).update();
    }

    /** 這一版讀到的電話；它讀的欄位是 NULL 就回 null —— 反例要看的就是這個。 */
    String phoneOf(JdbcClient db, UUID id) {
        return db.sql(select).param("id", id).query(String.class).list().getFirst();
    }
}
