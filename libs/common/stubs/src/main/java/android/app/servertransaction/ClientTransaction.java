package android.app.servertransaction;

import android.app.ClientTransactionHandler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

public class ClientTransaction {

    @Nullable
    public List<ClientTransactionItem> getCallbacks() {
        throw new RuntimeException("Stub!");
    }

    public void preExecute(@NonNull ClientTransactionHandler clientTransactionHandler) {
        throw new RuntimeException("Stub!");
    }
}
