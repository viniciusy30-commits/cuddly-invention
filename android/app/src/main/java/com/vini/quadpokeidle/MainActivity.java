package com.vini.quadpokeidle;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private final WebView[] views = new WebView[4];
    private final String url = "https://pokeidle.io/app";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 15, 28));
        getWindow().setNavigationBarColor(Color.rgb(9, 15, 28));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(9, 15, 28));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(12, 8, 12, 8);
        TextView title = new TextView(this);
        title.setText("QUAD POKEIDLE  •  4 PAINÉIS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(null, 1);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button reloadAll = new Button(this);
        reloadAll.setText("Recarregar");
        reloadAll.setOnClickListener(v -> { for (WebView w : views) if (w != null) w.reload(); });
        toolbar.addView(reloadAll);
        root.addView(toolbar);

        TextView note = new TextView(this);
        note.setText("Cada painel abre o jogo diretamente. O Android WebView pode compartilhar cookies entre painéis; múltiplas contas simultâneas ainda precisam ser verificadas.");
        note.setTextColor(Color.LTGRAY);
        note.setTextSize(11);
        note.setPadding(12, 0, 12, 8);
        root.addView(note);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setRowCount(2);
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        for (int i = 0; i < 4; i++) {
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundColor(Color.WHITE);
            TextView label = new TextView(this);
            label.setText("CONTA " + (i + 1) + "     ↻");
            label.setTextColor(Color.WHITE);
            label.setTextSize(12);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setPadding(8, 4, 8, 4);
            label.setBackgroundColor(Color.rgb(25, 38, 60));
            final int index = i;
            label.setOnClickListener(v -> { if (views[index] != null) views[index].reload(); });
            panel.addView(label, new LinearLayout.LayoutParams(-1, 32));

            WebView web = new WebView(this);
            views[i] = web;
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setDatabaseEnabled(true);
            web.getSettings().setLoadsImagesAutomatically(true);
            web.setWebViewClient(new WebViewClient());
            web.setWebChromeClient(new WebChromeClient());
            panel.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = 0; p.height = 0;
            p.columnSpec = GridLayout.spec(i % 2, 1f);
            p.rowSpec = GridLayout.spec(i / 2, 1f);
            p.setMargins(3, 3, 3, 3);
            grid.addView(panel, p);
            web.loadUrl(url);
        }
        setContentView(root);
    }

    @Override public void onBackPressed() {
        for (WebView w : views) if (w != null && w.canGoBack()) { w.goBack(); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        for (WebView w : views) if (w != null) { w.stopLoading(); w.destroy(); }
        super.onDestroy();
    }
}
