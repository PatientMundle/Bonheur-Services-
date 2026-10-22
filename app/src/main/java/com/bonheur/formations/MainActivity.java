package com.bonheur.formations;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.QueryProductDetailsParams;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Native staging app. READ-ONLY billing discovery: no charge is possible until
 * a verified purchase backend, login and entitlement restoration are completed.
 */
public final class MainActivity extends Activity {
    private static final int BLUE = Color.rgb(12, 81, 176);
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<Offer> offers = new ArrayList<>();
    private final Map<String, ProductDetails> playDetails = new HashMap<>();
    private LinearLayout content;
    private TextView status;
    private BillingClient billing;

    private static final class Offer {
        final String title;
        final String description;
        final String sku;
        final String type;

        Offer(String title, String description, String sku, String type) {
            this.title = title;
            this.description = description;
            this.sku = sku;
            this.type = type;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setPadding(dp(22), dp(24), dp(22), dp(24));
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.WHITE);
        scroll.addView(content);
        setContentView(scroll);

        // Android 16 enforces edge-to-edge. Inset content under system bars.
        if (Build.VERSION.SDK_INT >= 30) {
            content.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets system = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                content.setPadding(dp(22), dp(24) + system.top, dp(22), dp(24) + system.bottom);
                return insets;
            });
        }

        TextView heading = text("BONHEUR SERVICES", 15, BLUE);
        content.addView(heading);
        content.addView(text("Offres numériques", 27, Color.BLACK));
        content.addView(text("Catalogue et tarifs officiels de Google Play", 14, Color.DKGRAY));
        Button refresh = new Button(this);
        refresh.setText("Actualiser les offres");
        refresh.setOnClickListener(v -> loadOffers());
        content.addView(refresh);
        status = text("Chargement...", 14, Color.DKGRAY);
        content.addView(status);
        loadOffers();
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value + .5f);
    }

    private TextView text(String value, int size, int color) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(size);
        label.setPadding(0, dp(10), 0, dp(10));
        return label;
    }

    private void loadOffers() {
        String endpoint = BuildConfig.CATALOG_URL;
        if (!endpoint.startsWith("https://")) {
            status.setText("Catalogue non connecté. Configurez une URL HTTPS dans la compilation Android. Aucun paiement n'est proposé.");
            offers.clear();
            renderOffers();
            return;
        }
        status.setText("Synchronisation du catalogue...");
        network.execute(() -> {
            try {
                HttpsURLConnection conn = (HttpsURLConnection) new URL(endpoint).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                List<Offer> fetched = new ArrayList<>();
                try {
                    if (conn.getResponseCode() != 200) {
                        throw new IllegalStateException("HTTP " + conn.getResponseCode());
                    }
                    StringBuilder raw = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (raw.length() + line.length() > 1000000) {
                                throw new IllegalStateException("Catalogue trop volumineux");
                            }
                            raw.append(line);
                        }
                    }
                    JSONArray array = new JSONObject(raw.toString()).getJSONArray("offers");
                    for (int i = 0; i < array.length() && i < 100; i++) {
                        JSONObject item = array.getJSONObject(i);
                        String sku = item.optString("playProductId", "").trim();
                        String type = item.optString("playProductType", "inapp");
                        if (!type.equals("subs")) type = "inapp";
                        fetched.add(new Offer(
                            item.optString("title", "Offre"),
                            item.optString("description", ""),
                            sku, type));
                    }
                } finally {
                    conn.disconnect();
                }
                runOnUiThread(() -> {
                    offers.clear();
                    offers.addAll(fetched);
                    status.setText(offers.isEmpty() ? "Aucune offre publiée." : "Catalogue synchronisé.");
                    renderOffers();
                    queryPlayPrices();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    status.setText("Catalogue indisponible. Aucun paiement n'est possible. Réessayez.");
                    offers.clear();
                    renderOffers();
                });
            }
        });
    }

    private void queryPlayPrices() {
        if (billing == null) {
            billing = BillingClient.newBuilder(this)
                .setListener((result, purchases) -> {
                    // No checkout is implemented in this staging build.
                })
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build();
            billing.startConnection(new BillingClientStateListener() {
                @Override public void onBillingSetupFinished(BillingResult result) {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        refreshProductDetails();
                    } else {
                        runOnUiThread(() -> status.setText("Catalogue visible, tarifs Google Play momentanément indisponibles."));
                    }
                }
                @Override public void onBillingServiceDisconnected() {
                    // Automatic reconnection enabled.
                }
            });
        } else if (billing.isReady()) {
            refreshProductDetails();
        }
    }

    private void refreshProductDetails() {
        Set<String> used = new HashSet<>();
        List<QueryProductDetailsParams.Product> ids = new ArrayList<>();
        for (Offer offer : offers) {
            if (offer.sku.isEmpty()) continue;
            String key = offer.type + ":" + offer.sku;
            if (used.add(key)) {
                ids.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(offer.sku)
                    .setProductType(offer.type.equals("subs")
                        ? BillingClient.ProductType.SUBS
                        : BillingClient.ProductType.INAPP)
                    .build());
            }
        }
        if (ids.isEmpty()) return;
        billing.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(ids).build(),
            (result, detailsResult) -> runOnUiThread(() -> {
                playDetails.clear();
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    for (ProductDetails detail : detailsResult.getProductDetailsList()) {
                        playDetails.put(detail.getProductId(), detail);
                    }
                }
                renderOffers();
            })
        );
    }

    private String playPrice(Offer offer) {
        ProductDetails detail = playDetails.get(offer.sku);
        if (detail == null) return "Tarif Play non disponible";
        if (offer.type.equals("inapp") && detail.getOneTimePurchaseOfferDetailsList() != null
                && !detail.getOneTimePurchaseOfferDetailsList().isEmpty()) {
            return detail.getOneTimePurchaseOfferDetailsList().get(0).getFormattedPrice();
        }
        if (offer.type.equals("subs") && detail.getSubscriptionOfferDetails() != null
                && !detail.getSubscriptionOfferDetails().isEmpty()) {
            ProductDetails.PricingPhases phases =
                detail.getSubscriptionOfferDetails().get(0).getPricingPhases();
            if (!phases.getPricingPhaseList().isEmpty()) {
                return phases.getPricingPhaseList().get(0).getFormattedPrice();
            }
        }
        return "Tarif Play non disponible";
    }

    private void renderOffers() {
        // Retain header (title, subtitle, refresh, status) and replace only offer rows.
        while (content.getChildCount() > 5) content.removeViewAt(5);
        for (Offer offer : offers) {
            content.addView(text(offer.title, 20, BLUE));
            if (!offer.description.isEmpty()) content.addView(text(offer.description, 14, Color.DKGRAY));
            content.addView(text(playPrice(offer), 17, Color.BLACK));
            Button pay = new Button(this);
            pay.setText("Paiement bientôt disponible");
            pay.setEnabled(false);
            content.addView(pay);
        }
    }

    @Override protected void onDestroy() {
        if (billing != null) billing.endConnection();
        network.shutdownNow();
        super.onDestroy();
    }
}
