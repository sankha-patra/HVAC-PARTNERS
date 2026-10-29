package com.invoicepayments.billing;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import java.util.function.Supplier;

/** Test fixture setup and negative ambient-transaction tests only. Production flow tests have no outer template. */
final class BillingTestTransactions {
    private BillingTestTransactions(){}
    static <T> T run(BillingStore db,Supplier<T> action){
        var template=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.jdbc().getDataSource())));
        template.setTimeout(10);return template.execute(status->action.get());
    }
}
