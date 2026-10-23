package com.lucas.trippermeteor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class MainActivity extends Activity implements Core.Ui {

    static final int BG = 0xFF0B0D12, SURF = 0xFF151922, SURF2 = 0xFF1E2430, LINE = 0xFF2A3140, TXT = 0xFFF1F3F7,
            MUT = 0xFF8B94A6, ACC = 0xFFF5A524, ACC_DIM = 0xFF33270F, OK = 0xFF34D399, BAD = 0xFFEF4444, RED = 0xFFB3261E,
            TXC = 0xFF8AB4FF;

    private Core core;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences uiPrefs;

    private FrameLayout root;
    private LinearLayout home, navScreen;
    private ScrollView scroll;
    private final TextView[] tabBtn = new TextView[3];
    private final View[] tabs = new View[3];
    private boolean showHome = false;

    // início
    private TextView podTitle, podSub, radarInfo, backToNav, mapsSub;
    private View podDot;
    private Button podBtn;
    private LinearLayout results, recentBox;
    private EditText searchEt;

    // tela de navegação
    private TextView nvMode, nvPod, nvDist, nvTitle, nvSub, nvNext, nvRadarTxt, nvBottom, nvSpeed, nvSpeedUnit, nvErr, nvNotRadar;
    private ArrowView nvArrow, nvNextArrow, nvLimit;
    private LinearLayout nvRadar, nvNextRow, nvSpeedBox;
    private Button nvMaps, nvSim, nvStop;
    private long shownRadarId = 0;

    // ajustes
    private EditText radarIconEt, pinEt, repoEt;
    private TextView updTxt, updBanner;
    private int connectMode = 0;

    // ferramentas
    private TextView curBig, curHex, notesView, ctrlTxt, capTxt, logView;
    private EditText distEt, noteEt, rawEt;
    private Spinner iconSp, nextSp;
    private CheckBox nightCb, alertCb, autoCb;
    private int cur = 21, ctrlSub = 1, labBottom = 0;
    private final List<Integer> iconKeys = new ArrayList<>();
    private final TreeMap<Integer, String> notes = new TreeMap<>();
    private boolean suppressSpinner = false;

    private Runnable pendingAfterPerms;

    // ================= ciclo de vida =================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        uiPrefs = getSharedPreferences("ui", MODE_PRIVATE);
        core = Core.get(this);
        loadNotes();
        buildUi();
        core.setUi(this);
        for (int i = core.logs.size() - 1; i >= 0; i--) {
            String[] l = core.logs.get(i);
            appendLog(l[0], l[1], Integer.parseInt(l[2]));
        }
        onPodState(core.podState);
        onNav(core.lastStatus);
        ensurePerms(() -> {
            core.pod.openServer();
            if (core.podState == 0 && core.pod.address() != null) core.pod.connect();
        });
        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        quietUpdateCheck();
        refreshHome();
        refreshCaptures();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        core.setUi(null);
        h.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (core.navigating() && showHome) { showHome = false; updateScreens(); return; }
        super.onBackPressed();
    }

    // ================= permissões =================

    private String[] neededPerms() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> need = new ArrayList<>();
        for (String s : p) if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) need.add(s);
        return need.toArray(new String[0]);
    }

    private void ensurePerms(Runnable then) {
        String[] need = neededPerms();
        if (need.length == 0) { then.run(); return; }
        pendingAfterPerms = then;
        requestPermissions(need, 1);
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] res) {
        boolean btOk = Build.VERSION.SDK_INT < 31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED);
        if (!btOk) core.onLog("sem permissão de Dispositivos próximos: libere em Configurações > Apps > Tripper Meteor", Pod.LOG_ERR);
        if (!hasLocation()) core.onLog("sem permissão de Localização: a navegação precisa do GPS", Pod.LOG_ERR);
        Runnable r = pendingAfterPerms;
        pendingAfterPerms = null;
        if (r != null && btOk) r.run();
    }

    // ================= compartilhar do Google Maps =================

    private void handleIntent(Intent i) {
        if (i == null) return;
        String text = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            text = i.getStringExtra(Intent.EXTRA_TEXT);
            String subj = i.getStringExtra(Intent.EXTRA_SUBJECT);
            if (subj != null && text != null && !text.contains(subj)) text = subj + "\n" + text;
        } else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            text = i.getData().toString();
        }
        if (text == null || text.trim().isEmpty()) return;
        i.setAction(null);
        final String t = text;
        toast("Abrindo o local compartilhado…");
        double[] bias = lastKnown();
        io.execute(() -> {
            try {
                Router.Place p = Router.resolveShare(t, bias == null ? 0 : bias[0], bias == null ? 0 : bias[1]);
                h.post(() -> {
                    if (p == null) { toast("Não consegui entender o local compartilhado"); return; }
                    startNav(p);
                });
            } catch (Exception e) {
                h.post(() -> toast("Erro ao abrir o local: " + e.getMessage()));
            }
        });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ================= ações =================

    @SuppressLint("MissingPermission")
    private double[] lastKnown() {
        double[] f = core.nav.lastFix();
        if (f != null) return f;
        if (System.currentTimeMillis() - core.fixAt < 120000) return new double[]{core.fixLat, core.fixLon, core.fixHeading};
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) return new double[]{best.getLatitude(), best.getLongitude(), -1};
        } catch (Exception ignored) {}
        return null;
    }

    private void connectPod() {
        ensurePerms(() -> {
            core.pod.forceKnown = connectMode == 0 ? null : (connectMode == 1 ? Boolean.TRUE : Boolean.FALSE);
            core.pod.connect();
        });
    }

    private void afterStart() {
        if (core.podState == 0) core.pod.connect();
        showHome = false;
        updateScreens();
    }

    private void startNav(Router.Place p) {
        ensurePerms(() -> {
            if (!hasLocation()) { toast("Preciso da Localização pra navegar"); return; }
            core.bridge.setEnabled(false);
            core.addRecent(p);
            drawRecents();
            results.removeAllViews();
            hideKeyboard();
            NavService.start(this);
            core.nav.start(p);
            core.pod.persistent = true;
            afterStart();
        });
    }

    private void startMaps() {
        if (!MapsListener.isAllowed(this)) { askNotifAccess(); return; }
        ensurePerms(() -> {
            if (!hasLocation()) { toast("Preciso da Localização pro aviso de radar"); return; }
            core.startMapsMode();
            afterStart();
            openMaps();
        });
    }

    private void startRadarOnly() {
        ensurePerms(() -> {
            if (!hasLocation()) { toast("Preciso da Localização pro aviso de radar"); return; }
            core.startRadarOnly();
            afterStart();
        });
    }

    private void askNotifAccess() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Permitir leitura das notificações")
                .setMessage("Pra mostrar as setas do Google Maps e os alertas do Radarbot no pod, o Tripper lê as notificações desses apps.\n\nNa próxima tela, ative \"Tripper Meteor\" e volte pra cá.")
                .setPositiveButton("Abrir", (d, w) -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                .setNegativeButton("Agora não", null)
                .show();
    }

    private void openMaps() {
        Intent i = getPackageManager().getLaunchIntentForPackage(MapsListener.MAPS);
        if (i != null) startActivity(i);
        else toast("Google Maps não encontrado");
    }

    private void stopAll() {
        if (core.bridge.isEnabled()) core.stopMapsMode();
        if (core.nav.isActive()) core.nav.stop();
        core.finished();
        showHome = false;
        onNav(new Navigator.Status());
    }

    private void markRadar() {
        RadarStore.Radar r = core.markRadar();
        if (r == null) { toast("Sem GPS agora: tente de novo em movimento"); return; }
        core.beep(1);
        final String[] lims = {"Não sei", "30", "40", "50", "60", "70", "80", "90", "100", "110", "120"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Radar marcado. Qual o limite?")
                .setItems(lims, (d, w) -> {
                    if (w > 0) core.radars.setLimit(r.id, Integer.parseInt(lims[w]));
                    toast("Radar salvo" + (w > 0 ? " · " + lims[w] + " km/h" : ""));
                    refreshHome();
                })
                .show();
    }

    private void notRadar() {
        final long id = shownRadarId;
        if (id == 0) return;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Não é radar?")
                .setMessage("Esse radar sai da tua base e não avisa mais.")
                .setPositiveButton("Remover", (d, w) -> { core.ignoreRadar(id); toast("Radar removido"); })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void doSearch() {
        String q = searchEt.getText().toString().trim();
        if (q.isEmpty()) return;
        hideKeyboard();
        results.removeAllViews();
        results.addView(text("Buscando…", 13, MUT, false));
        double[] bias = lastKnown();
        io.execute(() -> {
            try {
                List<Router.Place> r = Router.geocode(q, bias == null ? 0 : bias[0], bias == null ? 0 : bias[1]);
                h.post(() -> showResults(r, bias));
            } catch (Exception e) {
                h.post(() -> { results.removeAllViews(); results.addView(text("Erro na busca: " + e.getMessage(), 13, BAD, false)); });
            }
        });
    }

    private void showResults(List<Router.Place> r, double[] bias) {
        results.removeAllViews();
        if (r.isEmpty()) { results.addView(text("Nada encontrado", 13, MUT, false)); return; }
        for (Router.Place p : r) {
            String d = "";
            if (bias != null) d = Navigator.niceDist((int) Geo.dist(bias[0], bias[1], p.lat, p.lon)).replace(" metros", " m");
            results.addView(placeRow(p, d));
        }
    }

    private View placeRow(Router.Place p, String dist) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(round(SURF2, 0, 14));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(p.name, 15, TXT, true));
        if (p.detail != null && !p.detail.isEmpty()) tx.addView(text(p.detail, 12, MUT, false));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!dist.isEmpty()) row.addView(text(dist, 13, ACC, true));
        row.setOnClickListener(v -> startNav(p));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private void drawRecents() {
        recentBox.removeAllViews();
        List<Router.Place> r = core.recents();
        if (r.isEmpty()) return;
        recentBox.addView(caption("RECENTES"));
        for (Router.Place p : r) recentBox.addView(placeRow(p, ""));
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (getCurrentFocus() != null) im.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }

    // ================= Core.Ui =================

    @Override
    public void onLog(String time, String line, int kind) { appendLog(time, line, kind); }

    private final SpannableStringBuilder logBuf = new SpannableStringBuilder();
    private int logLines = 0;

    private void appendLog(String time, String line, int kind) {
        int color = kind == Pod.LOG_TX ? TXC : kind == Pod.LOG_RX ? OK : kind == Pod.LOG_ERR ? BAD : MUT;
        SpannableStringBuilder s = new SpannableStringBuilder(time + "  " + line + "\n");
        s.setSpan(new ForegroundColorSpan(color), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        logBuf.insert(0, s);
        if (++logLines > 300) {
            int cut = logBuf.toString().lastIndexOf('\n', logBuf.length() - 2);
            if (cut > 0) logBuf.delete(cut + 1, logBuf.length());
            logLines--;
        }
        if (logView != null) logView.setText(logBuf);
    }

    @Override
    public void onPodState(int s) {
        String[] t = {"Pod desconectado", "Procurando o pod…", "Conectando…", "Pod conectado"};
        int[] c = {BAD, ACC, ACC, OK};
        podTitle.setText(t[s]);
        podSub.setText(s == 0 ? "Ligue a ignição e toque em Conectar" : s == 3 ? "RE_DISP · " + core.pod.address() : "Ignição ligada?");
        ((GradientDrawable) podDot.getBackground()).setColor(c[s]);
        podBtn.setText(s == 0 ? "Conectar" : "Desconectar");
        podBtn.setBackground(round(s == 0 ? ACC : SURF2, 0, 12));
        podBtn.setTextColor(s == 0 ? 0xFF1A1300 : TXT);
        nvPod.setText("●  Pod");
        nvPod.setTextColor(c[s]);
    }

    @Override
    public void onNav(Navigator.Status s) {
        updateScreens();
        if (!core.navigating()) return;
        boolean maps = core.bridge.isEnabled() && !core.bridge.radarOnly;
        boolean radarOnly = core.bridge.isEnabled() && core.bridge.radarOnly;
        nvMode.setText(maps ? "Google Maps" : radarOnly ? "Modo radar" : "Rota do Tripper");

        boolean showArrow = s.icon >= 0;
        nvArrow.setVisibility(showArrow ? View.VISIBLE : View.GONE);
        nvSpeedBox.setVisibility(showArrow ? View.GONE : View.VISIBLE);
        if (showArrow) nvArrow.setIcon(s.icon, s.exit);
        nvSpeed.setText(s.speedKmh >= 0 ? String.valueOf(s.speedKmh) : "--");

        nvDist.setText(s.dist >= 0 ? Navigator.niceDist(s.dist).replace(" metros", " m") : "");
        nvDist.setVisibility(s.dist >= 0 ? View.VISIBLE : View.GONE);
        nvTitle.setText(s.title);
        nvSub.setText(s.sub);
        nvSub.setVisibility(s.sub == null || s.sub.isEmpty() ? View.GONE : View.VISIBLE);

        nvNextRow.setVisibility(s.nextIcon >= 0 ? View.VISIBLE : View.GONE);
        if (s.nextIcon >= 0) { nvNextArrow.setIcon(s.nextIcon, 0); nvNext.setText("Depois: " + Proto.iconName(s.nextIcon)); }

        boolean radar = s.radar != null && !s.radar.isEmpty();
        nvRadar.setVisibility(radar ? View.VISIBLE : View.GONE);
        if (radar) {
            nvRadarTxt.setText(s.radar);
            nvLimit.setLimit(s.limit);
            nvLimit.setVisibility(s.limit > 0 ? View.VISIBLE : View.GONE);
        }
        shownRadarId = radar ? s.radarId : 0;
        nvNotRadar.setVisibility(shownRadarId != 0 ? View.VISIBLE : View.GONE);

        nvBottom.setText(s.bottom == null || s.bottom.isEmpty() ? (radarOnly ? "Radares: " + core.radars.size() + " na base" : "") : s.bottom);
        nvBottom.setVisibility(nvBottom.getText().length() == 0 ? View.GONE : View.VISIBLE);
        nvErr.setText(s.error == null ? "" : s.error);
        nvErr.setVisibility(s.error == null || s.error.isEmpty() ? View.GONE : View.VISIBLE);
        nvMaps.setVisibility(maps ? View.VISIBLE : View.GONE);
        nvSim.setVisibility(!maps && !radarOnly ? View.VISIBLE : View.GONE);
        nvSim.setText(core.nav.isSimulating() ? "Parar simulação" : "Simular");
    }

    @Override
    public void onPinResult(boolean ok) { toast(ok ? "PIN aceito! Pod pareado." : "PIN recusado"); }

    private void updateScreens() {
        boolean nav = core.navigating();
        navScreen.setVisibility(nav && !showHome ? View.VISIBLE : View.GONE);
        home.setVisibility(nav && !showHome ? View.GONE : View.VISIBLE);
        backToNav.setVisibility(nav && showHome ? View.VISIBLE : View.GONE);
    }

    private void refreshHome() {
        if (radarInfo == null) return;
        boolean allowed = MapsListener.isAllowed(this);
        mapsSub.setText(allowed ? "Rota de moto com trânsito. O pod mostra as setas e o Tripper avisa os radares."
                : "Toque para permitir que o Tripper leia a navegação do Maps.");
        radarInfo.setText(core.radars.size() + " radares na base  ·  " + core.radars.userCount() + " marcados por você\n"
                + "Radarbot: " + (!core.set.useRadarbot ? "desligado nos ajustes" : allowed ? "alertas ligados (deixe o Radarbot aberto)" : "precisa da leitura de notificações"));
    }

    // ================= atualização =================

    private void checkUpdate() {
        String repo = Router.cleanRepo(repoEt.getText().toString());
        if (!repo.contains("/")) { updTxt.setText("Coloque o repositório no formato usuario/projeto"); return; }
        repoEt.setText(repo);
        uiPrefs.edit().putString("repo", repo).apply();
        updTxt.setText("Procurando…");
        io.execute(() -> {
            try {
                Updater.Release r = Updater.latest(repo);
                int mine = Updater.currentBuild(this);
                h.post(() -> {
                    if (r.build <= mine) { updTxt.setText("Você já está na última versão (" + Updater.currentName(this) + ")"); return; }
                    updTxt.setText("Nova versão: " + r.name + (r.notes.isEmpty() ? "" : "\n" + r.notes));
                    if (!Updater.canInstall(this)) {
                        updTxt.append("\n\nLibere \"Instalar apps desconhecidos\" pro Tripper Meteor e toque de novo.");
                        return;
                    }
                    io.execute(() -> {
                        try {
                            Updater.install(this, r, msg -> h.post(() -> updTxt.setText(msg)));
                        } catch (Exception e) {
                            h.post(() -> updTxt.setText("Falhou: " + e.getMessage()));
                        }
                    });
                });
            } catch (Exception e) {
                h.post(() -> updTxt.setText("Não achei atualização: " + e.getMessage()));
            }
        });
    }

    static final String DEFAULT_REPO = "MrD00oug/165111";

    private String repo() {
        String r = Router.cleanRepo(uiPrefs.getString("repo", ""));
        return r.contains("/") ? r : DEFAULT_REPO;
    }

    /** Ao abrir o app, olha (no máximo 1x a cada 6 h) se tem versão nova. */
    private void quietUpdateCheck() {
        long last = uiPrefs.getLong("lastUpdCheck", 0);
        if (System.currentTimeMillis() - last < 6 * 3600 * 1000L) {
            boolean still = uiPrefs.getInt("newBuild", 0) > Updater.currentBuild(this);
            showUpdateBanner(still ? uiPrefs.getString("newVersion", null) : null);
            return;
        }
        uiPrefs.edit().putLong("lastUpdCheck", System.currentTimeMillis()).apply();
        final String repo = repo();
        io.execute(() -> {
            try {
                Updater.Release r = Updater.latest(repo);
                boolean newer = r.build > Updater.currentBuild(this);
                h.post(() -> {
                    uiPrefs.edit().putString("newVersion", newer ? r.name : null).putInt("newBuild", r.build).apply();
                    showUpdateBanner(newer ? r.name : null);
                });
            } catch (Exception ignored) {}
        });
    }

    private void showUpdateBanner(String version) {
        if (updBanner == null) return;
        boolean show = version != null && !core.navigating();
        updBanner.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) updBanner.setText("Versão " + version + " disponível · toque para atualizar");
    }

    // ================= bancada =================

    private int intOf(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception ex) { return def; }
    }

    private void labSend() {
        if (!core.pod.isConnected()) { core.onLog("conecte no pod primeiro", Pod.LOG_ERR); return; }
        int p = nextSp.getSelectedItemPosition();
        int next = p <= 0 ? -1 : iconKeys.get(p - 1);
        byte[] f = Proto.nav(cur, intOf(distEt, 200), -1, next, alertCb.isChecked() ? 2 : 1, labBottom, 15, 5000, nightCb.isChecked());
        core.pod.setKeepalive(f);
        core.pod.send(f, String.format(Locale.US, "ícone %d (0x%02X)", cur, cur));
    }

    private void step(int d) {
        cur = (cur + d + 256) % 256;
        refreshCur();
        if (core.pod.isConnected()) labSend();
    }

    private void refreshCur() {
        curBig.setText(String.valueOf(cur));
        String note = notes.get(cur);
        curHex.setText(String.format(Locale.US, "0x%02X · %s%s", cur, Proto.iconName(cur), note != null ? "\n✎ " + note : ""));
        int idx = iconKeys.indexOf(cur);
        if (idx >= 0 && iconSp.getSelectedItemPosition() != idx) { suppressSpinner = true; iconSp.setSelection(idx); }
    }

    private final Runnable autoRun = new Runnable() {
        @Override public void run() {
            if (!autoCb.isChecked()) return;
            step(1);
            h.postDelayed(this, 3000);
        }
    };

    private void ctrlStep(int d) {
        ctrlSub = (ctrlSub + d + 256) % 256;
        ctrlTxt.setText(String.format(Locale.US, "sub %d (0x%02X)", ctrlSub, ctrlSub));
        if (core.pod.isConnected()) {
            byte[] f = Proto.ctrl(ctrlSub);
            core.pod.setKeepalive(f);
            core.pod.send(f, "tela 0x40 sub " + ctrlSub);
        }
    }

    private String notesText() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : notes.entrySet())
            sb.append(String.format(Locale.US, "%d (0x%02X): %s\n", e.getKey(), e.getKey(), e.getValue()));
        return sb.length() == 0 ? "(sem anotações)" : sb.toString();
    }

    private void saveNotes() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : notes.entrySet())
            sb.append(e.getKey()).append('\t').append(e.getValue().replace('\n', ' ')).append('\n');
        uiPrefs.edit().putString("notes", sb.toString()).apply();
        if (notesView != null) notesView.setText(notesText());
    }

    private void loadNotes() {
        for (String line : uiPrefs.getString("notes", "").split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) try { notes.put(Integer.parseInt(line.substring(0, tab)), line.substring(tab + 1)); } catch (Exception ignored) {}
        }
    }

    private void refreshCaptures() {
        if (capTxt == null) return;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String c : core.captures) {            // mostra primeiro as do Radarbot/Waze
            if (c.startsWith("MAPS", 9)) continue;
            sb.append(c).append("\n\n");
            if (++n >= 4) break;
        }
        for (String c : core.captures) {
            if (n >= 5) break;
            if (!c.startsWith("MAPS", 9)) continue;
            sb.append(c).append("\n\n");
            n++;
        }
        capTxt.setText(core.captures.isEmpty() ? "Nenhuma ainda. Ative um modo e use o Maps, o Radarbot ou o Waze." :
                core.captureSummary() + "\n\n" + sb);
    }

    private void copy(String label, String t) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, t));
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show();
    }

    // ================= componentes =================

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private TextView caption(String s) {
        TextView t = text(s, 12, MUT, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(18), 0, dp(8));
        return t;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(round(SURF, 0, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        parent.addView(c, lp);
        return c;
    }

    private Button btn(String t, boolean primary, View.OnClickListener on) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? 0xFF1A1300 : TXT);
        b.setBackground(round(primary ? ACC : SURF2, 0, 14));
        b.setMinHeight(dp(52));
        b.setMinimumHeight(dp(52));
        b.setStateListAnimator(null);
        b.setOnClickListener(on);
        return b;
    }

    private EditText edit(String hint, int type, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF606A7B);
        e.setTextColor(TXT);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setBackground(round(SURF2, 0, 14));
        e.setPadding(dp(16), dp(14), dp(16), dp(14));
        if (value != null) e.setText(value);
        return e;
    }

    private LinearLayout row(View... vs) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < vs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            r.addView(vs[i], lp);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(10);
        r.setLayoutParams(rp);
        return r;
    }

    private LinearLayout col(View... vs) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        for (View v : vs) c.addView(v);
        return c;
    }

    private void add(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(topDp);
        parent.addView(v, lp);
    }

    /** Seletor em pílulas (substitui as listas suspensas). */
    private LinearLayout seg(String[] opts, int sel, IntConsumer on) {
        LinearLayout g = new LinearLayout(this);
        g.setPadding(dp(4), dp(4), dp(4), dp(4));
        g.setBackground(round(SURF2, 0, 14));
        final TextView[] items = new TextView[opts.length];
        for (int i = 0; i < opts.length; i++) {
            final int k = i;
            TextView t = text(opts[i], 14, MUT, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(4), dp(11), dp(4), dp(11));
            t.setOnClickListener(v -> {
                for (int j = 0; j < items.length; j++) paintSeg(items[j], j == k);
                on.accept(k);
            });
            items[i] = t;
            paintSeg(t, i == sel);
            g.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return g;
    }

    private void paintSeg(TextView t, boolean on) {
        t.setBackground(on ? round(ACC, 0, 11) : null);
        t.setTextColor(on ? 0xFF1A1300 : MUT);
    }

    /** Linha com título, explicação e chave liga/desliga. */
    private View switchRow(String title, String sub, boolean on, Consumer<Boolean> change) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, dp(10));
        LinearLayout tx = col(text(title, 15, TXT, false));
        if (sub != null) tx.addView(text(sub, 12, MUT, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(on);
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{ACC, 0xFFB0B7C3}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{0x88F5A524, 0x55FFFFFF}));
        sw.setOnCheckedChangeListener((b, v) -> change.accept(v));
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        return r;
    }

    private View settingLabel(String s) {
        TextView t = text(s, 14, TXT, false);
        t.setPadding(0, dp(12), 0, dp(8));
        return t;
    }

    /** Cartão grande de modo de navegação. */
    private TextView modeTile(LinearLayout parent, View icon, String title, String sub, boolean highlight, View.OnClickListener on) {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), dp(16), dp(14), dp(16));
        t.setBackground(round(highlight ? 0xFF1F1A0F : SURF, highlight ? 0xFF5A4316 : 0, 20));
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(round(highlight ? ACC_DIM : SURF2, 0, 16));
        ic.addView(icon, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        t.addView(ic, new LinearLayout.LayoutParams(dp(54), dp(54)));
        TextView subT = text(sub, 13, MUT, false);
        LinearLayout tx = col(text(title, 17, TXT, true), subT);
        tx.setPadding(dp(14), 0, dp(8), 0);
        t.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        t.addView(text("›", 28, highlight ? ACC : MUT, false));
        t.setOnClickListener(on);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        parent.addView(t, lp);
        return subT;
    }

    private ArrowView arrow(int icon, int color) {
        ArrowView a = new ArrowView(this);
        a.setColor(color);
        a.setIcon(icon, 0);
        return a;
    }

    // ================= montagem =================

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        navScreen = buildNavScreen();
        root.addView(navScreen, new FrameLayout.LayoutParams(-1, -1));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(10), dp(16), dp(24));
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));

        backToNav = text("●  Navegação ativa · toque para voltar", 15, 0xFF1A1300, true);
        backToNav.setGravity(Gravity.CENTER);
        backToNav.setPadding(dp(12), dp(14), dp(12), dp(14));
        backToNav.setBackgroundColor(ACC);
        backToNav.setOnClickListener(v -> { showHome = false; updateScreens(); });
        home.addView(backToNav, new LinearLayout.LayoutParams(-1, -2));
        home.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        tabs[0] = buildHomeTab(body);
        tabs[1] = buildSettingsTab(body);
        tabs[2] = buildToolsTab(body);

        // barra de abas
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(SURF);
        bar.setPadding(dp(10), dp(8), dp(10), dp(10));
        String[] tn = {"Início", "Ajustes", "Ferramentas"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            TextView t = text(tn[i], 14, MUT, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setOnClickListener(v -> selectTab(k));
            tabBtn[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            bar.addView(t, lp);
        }
        home.addView(bar);

        setContentView(root);
        selectTab(uiPrefs.getInt("tab", 0));
        updateScreens();
    }

    private void selectTab(int i) {
        for (int k = 0; k < 3; k++) {
            tabs[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            tabBtn[k].setTextColor(k == i ? ACC : MUT);
            tabBtn[k].setBackground(k == i ? round(ACC_DIM, 0, 14) : null);
        }
        uiPrefs.edit().putInt("tab", i).apply();
        if (i == 0) refreshHome();
        if (i == 2) refreshCaptures();
        if (scroll != null) scroll.scrollTo(0, 0);
    }

    // ---------- início ----------

    private View buildHomeTab(LinearLayout body) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        body.addView(tab);

        TextView title = text("Tripper", 30, TXT, true);
        title.setPadding(dp(4), dp(10), 0, 0);
        tab.addView(title);
        TextView sub = text("Super Meteor 650", 14, MUT, false);
        sub.setPadding(dp(4), 0, 0, dp(12));
        tab.addView(sub);

        // pod
        LinearLayout pc = new LinearLayout(this);
        pc.setGravity(Gravity.CENTER_VERTICAL);
        pc.setPadding(dp(16), dp(14), dp(14), dp(14));
        pc.setBackground(round(SURF, 0, 20));
        podDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(BAD);
        podDot.setBackground(dot);
        pc.addView(podDot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        podTitle = text("", 16, TXT, true);
        podSub = text("", 12, MUT, false);
        LinearLayout ptx = col(podTitle, podSub);
        ptx.setPadding(dp(14), 0, dp(8), 0);
        pc.addView(ptx, new LinearLayout.LayoutParams(0, -2, 1f));
        podBtn = btn("Conectar", true, v -> { if (core.podState == 0) connectPod(); else core.pod.disconnect(); });
        podBtn.setMinHeight(dp(42));
        podBtn.setMinimumHeight(dp(42));
        podBtn.setTextSize(14);
        pc.addView(podBtn, new LinearLayout.LayoutParams(-2, dp(42)));
        tab.addView(pc, new LinearLayout.LayoutParams(-1, -2));

        updBanner = text("", 14, 0xFF1A1300, true);
        updBanner.setPadding(dp(16), dp(14), dp(16), dp(14));
        updBanner.setBackground(round(ACC, 0, 16));
        updBanner.setVisibility(View.GONE);
        updBanner.setOnClickListener(v -> { selectTab(1); checkUpdate(); });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-1, -2);
        ulp.topMargin = dp(10);
        tab.addView(updBanner, ulp);

        tab.addView(caption("NAVEGAR"));
        mapsSub = modeTile(tab, arrow(21, ACC), "Google Maps", "", true, v -> startMaps());
        ArrowView radar = new ArrowView(this);
        radar.setColor(TXT);
        radar.setRadar();
        modeTile(tab, radar, "Só radar", "Já sabe o caminho? O pod mostra só os radares, com bipe.", false, v -> startRadarOnly());

        LinearLayout own = card(tab);
        LinearLayout oh = new LinearLayout(this);
        oh.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(round(SURF2, 0, 16));
        ic.addView(arrow(9, TXT), new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        oh.addView(ic, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout otx = col(text("Rota do Tripper", 17, TXT, true), text("Sem o Maps e sem trânsito. Para onde?", 13, MUT, false));
        otx.setPadding(dp(14), 0, 0, 0);
        oh.addView(otx, new LinearLayout.LayoutParams(0, -2, 1f));
        own.addView(oh);
        searchEt = edit("Endereço ou lugar", InputType.TYPE_CLASS_TEXT, null);
        searchEt.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchEt.setOnEditorActionListener((v, a, e) -> {
            if (a == EditorInfo.IME_ACTION_SEARCH || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { doSearch(); return true; }
            return false;
        });
        Button bs = btn("Buscar", false, v -> doSearch());
        LinearLayout sr = row(searchEt, bs);
        ((LinearLayout.LayoutParams) bs.getLayoutParams()).weight = 0.38f;
        own.addView(sr);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        own.addView(results);
        recentBox = new LinearLayout(this);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        own.addView(recentBox);
        drawRecents();

        tab.addView(caption("RADARES"));
        LinearLayout rc = card(tab);
        radarInfo = text("", 14, TXT, false);
        rc.addView(radarInfo);
        rc.addView(text("Passou num radar que não avisou? Durante a navegação, toque em \"Marcar radar\".", 12, MUT, false));
        refreshHome();
        return tab;
    }

    // ---------- tela de navegação ----------

    private LinearLayout buildNavScreen() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setBackgroundColor(BG);
        s.setPadding(dp(18), dp(14), dp(18), dp(16));

        // topo
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        nvPod = text("●  Pod", 13, BAD, true);
        nvPod.setPadding(dp(12), dp(7), dp(12), dp(7));
        nvPod.setBackground(round(SURF, 0, 20));
        nvPod.setOnClickListener(v -> { if (core.podState == 0) connectPod(); });
        top.addView(nvPod);
        nvMode = text("", 14, MUT, true);
        nvMode.setPadding(dp(12), 0, 0, 0);
        top.addView(nvMode, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView menu = text("Menu", 14, TXT, true);
        menu.setPadding(dp(14), dp(8), dp(14), dp(8));
        menu.setBackground(round(SURF, 0, 20));
        menu.setOnClickListener(v -> { showHome = true; updateScreens(); });
        top.addView(menu);
        s.addView(top);

        // centro
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        nvArrow = new ArrowView(this);
        nvArrow.setColor(ACC);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(190), dp(190));
        alp.topMargin = dp(20);
        center.addView(nvArrow, alp);
        nvSpeed = text("--", 96, TXT, true);
        nvSpeed.setIncludeFontPadding(false);
        nvSpeed.setGravity(Gravity.CENTER);
        nvSpeedUnit = text("km/h", 16, MUT, true);
        nvSpeedUnit.setGravity(Gravity.CENTER);
        nvSpeedBox = col(nvSpeed, nvSpeedUnit);
        nvSpeedBox.setGravity(Gravity.CENTER_HORIZONTAL);
        nvSpeedBox.setPadding(0, dp(36), 0, dp(10));
        center.addView(nvSpeedBox, new LinearLayout.LayoutParams(-2, -2));
        nvDist = text("", 64, ACC, true);
        nvDist.setIncludeFontPadding(false);
        nvDist.setGravity(Gravity.CENTER);
        center.addView(nvDist, new LinearLayout.LayoutParams(-2, -2));
        nvTitle = text("", 21, TXT, true);
        nvTitle.setGravity(Gravity.CENTER);
        nvTitle.setPadding(0, dp(6), 0, 0);
        center.addView(nvTitle, new LinearLayout.LayoutParams(-1, -2));
        nvSub = text("", 15, MUT, false);
        nvSub.setGravity(Gravity.CENTER);
        center.addView(nvSub, new LinearLayout.LayoutParams(-1, -2));

        nvNextRow = new LinearLayout(this);
        nvNextRow.setGravity(Gravity.CENTER_VERTICAL);
        nvNextRow.setPadding(dp(12), dp(8), dp(14), dp(8));
        nvNextRow.setBackground(round(SURF, 0, 20));
        nvNextArrow = arrow(9, MUT);
        nvNextRow.addView(nvNextArrow, new LinearLayout.LayoutParams(dp(26), dp(26)));
        nvNext = text("", 14, MUT, false);
        nvNext.setPadding(dp(8), 0, 0, 0);
        nvNextRow.addView(nvNext);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = dp(14);
        center.addView(nvNextRow, nlp);
        s.addView(center, new LinearLayout.LayoutParams(-1, -2));

        // radar
        nvRadar = new LinearLayout(this);
        nvRadar.setGravity(Gravity.CENTER_VERTICAL);
        nvRadar.setPadding(dp(14), dp(12), dp(12), dp(12));
        nvRadar.setBackground(round(RED, 0, 20));
        ArrowView ri = new ArrowView(this);
        ri.setColor(0xFFFFFFFF);
        ri.setRadar();
        nvRadar.addView(ri, new LinearLayout.LayoutParams(dp(40), dp(40)));
        nvRadarTxt = text("", 17, 0xFFFFFFFF, true);
        LinearLayout rtx = col(nvRadarTxt);
        nvNotRadar = text("Não é radar?", 13, 0xFFFFD6D3, false);
        nvNotRadar.setPadding(0, dp(4), 0, 0);
        nvNotRadar.setOnClickListener(v -> notRadar());
        rtx.addView(nvNotRadar);
        rtx.setPadding(dp(12), 0, dp(8), 0);
        nvRadar.addView(rtx, new LinearLayout.LayoutParams(0, -2, 1f));
        nvLimit = new ArrowView(this);
        nvRadar.addView(nvLimit, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(18);
        s.addView(nvRadar, rlp);

        nvErr = text("", 13, BAD, false);
        nvErr.setGravity(Gravity.CENTER);
        nvErr.setPadding(0, dp(8), 0, 0);
        s.addView(nvErr, new LinearLayout.LayoutParams(-1, -2));

        s.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));

        // base
        nvBottom = text("", 17, TXT, true);
        nvBottom.setGravity(Gravity.CENTER);
        nvBottom.setPadding(dp(14), dp(14), dp(14), dp(14));
        nvBottom.setBackground(round(SURF, 0, 18));
        s.addView(nvBottom, new LinearLayout.LayoutParams(-1, -2));
        Button mark = btn("Marcar radar", false, v -> markRadar());
        nvMaps = btn("Abrir Maps", true, v -> openMaps());
        nvSim = btn("Simular", false, v -> core.nav.setSimulation(!core.nav.isSimulating(), 40));
        LinearLayout r1 = row(mark, nvMaps, nvSim);
        s.addView(r1);
        nvStop = btn("Parar", false, v -> stopAll());
        nvStop.setTextColor(0xFFFF8A80);
        s.addView(row(nvStop));
        s.setVisibility(View.GONE);
        return s;
    }

    // ---------- ajustes ----------

    private View buildSettingsTab(LinearLayout body) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        body.addView(tab);
        Navigator.Settings s = core.set;
        TextView title = text("Ajustes", 28, TXT, true);
        title.setPadding(dp(4), dp(10), 0, dp(4));
        tab.addView(title);

        tab.addView(caption("TELA DO POD"));
        LinearLayout c = card(tab);
        c.addView(settingLabel("Embaixo da seta"));
        c.addView(seg(new String[]{"Nada", "Chegada", "Km"}, s.bottom, k -> { s.bottom = k; core.saveSettings(); }));
        c.addView(settingLabel("Fundo escuro (modo noite)"));
        c.addView(seg(new String[]{"Automático", "Sempre", "Nunca"}, s.night, k -> { s.night = k; core.saveSettings(); }));
        add(c, switchRow("Número da saída na rotatória", "Usa os ícones 50–58. Teste na Bancada antes.", s.exitIcons,
                v -> { s.exitIcons = v; core.saveSettings(); }), 8);

        tab.addView(caption("RADAR"));
        LinearLayout c2 = card(tab);
        c2.addView(switchRow("Usar os alertas do Radarbot", "Deixe o Radarbot aberto no fundo. O alerta dele vai pro pod.", s.useRadarbot,
                v -> { s.useRadarbot = v; core.saveSettings(); refreshHome(); }));
        c2.addView(switchRow("Falar o aviso", "Voz no fone do capacete", s.voiceRadar, v -> { s.voiceRadar = v; core.saveSettings(); }));
        c2.addView(switchRow("Bipe", "Ao avisar, perto do radar e acima do limite", s.beepRadar, v -> { s.beepRadar = v; core.saveSettings(); }));
        c2.addView(settingLabel("Avisar a partir de"));
        final int[] dists = {300, 500, 800};
        c2.addView(seg(new String[]{"300 m", "500 m", "800 m"}, s.radarDist <= 300 ? 0 : s.radarDist <= 500 ? 1 : 2,
                k -> { s.radarDist = dists[k]; core.saveSettings(); }));
        c2.addView(settingLabel("Ícone do radar no pod"));
        radarIconEt = edit("número", InputType.TYPE_CLASS_NUMBER, String.valueOf(s.radarIcon));
        Button bt = btn("Testar no pod", false, v -> {
            s.radarIcon = Math.max(0, Math.min(255, intOf(radarIconEt, 28)));
            core.saveSettings();
            if (!core.pod.isConnected()) { toast("Conecte no pod pra testar"); return; }
            byte[] f = Proto.nav(s.radarIcon, 300, -1, -1, 2, Proto.BOTTOM_TOTAL, 0, 60, core.nav.night());
            core.pod.setKeepalive(f);
            core.pod.send(f, "teste radar (300 m, limite 60)");
            core.beep(1);
            core.say("Radar em 300 metros, limite 60");
        });
        LinearLayout ir = row(radarIconEt, bt);
        ((LinearLayout.LayoutParams) radarIconEt.getLayoutParams()).weight = 0.6f;
        c2.addView(ir);

        tab.addView(caption("VOZ"));
        LinearLayout c3 = card(tab);
        c3.addView(switchRow("Falar as curvas", "Só na rota do Tripper (o Maps tem a voz dele)", s.voiceTurns,
                v -> { s.voiceTurns = v; core.saveSettings(); }));

        tab.addView(caption("POD"));
        LinearLayout c4 = card(tab);
        c4.addView(settingLabel("Ao conectar"));
        c4.addView(seg(new String[]{"Automático", "Retomar", "Pedir PIN"}, 0, k -> connectMode = k));
        pinEt = edit("PIN de 6 dígitos", InputType.TYPE_CLASS_NUMBER, null);
        Button bPin = btn("Enviar PIN", false, v -> {
            String d = pinEt.getText().toString().replaceAll("\\D", "");
            if (d.isEmpty() || !core.pod.isConnected()) { toast("Conecte e digite o PIN"); return; }
            core.pod.send(Proto.pin(d), "PIN");
        });
        c4.addView(row(pinEt, bPin));
        c4.addView(row(btn("Esquecer pod", false, v -> { core.pod.forgetPairing(); core.pod.forgetDevice(); toast("Pod esquecido"); })));

        tab.addView(caption("ATUALIZAÇÕES"));
        LinearLayout c5 = card(tab);
        c5.addView(text("Versão " + Updater.currentName(this) + " (build " + Updater.currentBuild(this) + ")", 14, TXT, false));
        c5.addView(settingLabel("Repositório no GitHub"));
        repoEt = edit(DEFAULT_REPO, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, repo());
        c5.addView(repoEt);
        c5.addView(row(btn("Procurar atualização", true, v -> checkUpdate())));
        updTxt = text("", 13, TXT, false);
        updTxt.setPadding(0, dp(8), 0, 0);
        c5.addView(updTxt);
        return tab;
    }

    // ---------- ferramentas ----------

    private View buildToolsTab(LinearLayout body) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        body.addView(tab);
        TextView title = text("Ferramentas", 28, TXT, true);
        title.setPadding(dp(4), dp(10), 0, 0);
        tab.addView(title);
        TextView hint = text("Para testes. Com a navegação ligada, o pod segue a rota.", 13, MUT, false);
        hint.setPadding(dp(4), 0, 0, dp(4));
        tab.addView(hint);

        tab.addView(caption("NOTIFICAÇÕES LIDAS (MAPS, RADARBOT, WAZE)"));
        LinearLayout c6 = card(tab);
        c6.addView(text("Se uma seta ou um alerta sair errado, copie e me mande.", 13, MUT, false));
        capTxt = text("", 11, TXT, false);
        capTxt.setTypeface(Typeface.MONOSPACE);
        capTxt.setPadding(0, dp(8), 0, 0);
        c6.addView(capTxt);
        c6.addView(row(btn("Atualizar", false, v -> refreshCaptures()),
                btn("Copiar tudo", true, v -> copy("capturas", String.join("\n", core.captures)))));

        tab.addView(caption("ÍCONES DO POD"));
        LinearLayout c2 = card(tab);
        curBig = text("", 52, ACC, true);
        curBig.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        curBig.setGravity(Gravity.CENTER);
        curHex = text("", 13, MUT, false);
        curHex.setGravity(Gravity.CENTER);
        Button bMinus = btn("−", false, v -> step(-1));
        bMinus.setTextSize(24);
        Button bPlus = btn("+", false, v -> step(1));
        bPlus.setTextSize(24);
        LinearLayout big = new LinearLayout(this);
        big.setGravity(Gravity.CENTER_VERTICAL);
        big.addView(bMinus, new LinearLayout.LayoutParams(dp(60), dp(60)));
        big.addView(col(curBig, curHex), new LinearLayout.LayoutParams(0, -2, 1f));
        big.addView(bPlus, new LinearLayout.LayoutParams(dp(60), dp(60)));
        c2.addView(big);
        List<String> iconNames = new ArrayList<>();
        for (Map.Entry<Integer, String> e : Proto.ICONS.entrySet()) {
            iconKeys.add(e.getKey());
            iconNames.add(e.getKey() + " · " + e.getValue());
        }
        iconSp = spinner(iconNames);
        iconSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (suppressSpinner) { suppressSpinner = false; return; }
                cur = iconKeys.get(pos);
                refreshCur();
                if (core.pod.isConnected()) labSend();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        add(c2, iconSp, 10);
        distEt = edit("metros", InputType.TYPE_CLASS_NUMBER, "200");
        List<String> nextNames = new ArrayList<>();
        nextNames.add("Próxima: nenhuma");
        nextNames.addAll(iconNames);
        nextSp = spinner(nextNames);
        c2.addView(row(distEt, nextSp));
        add(c2, seg(new String[]{"Embaixo: nada", "Hora", "5 km"}, 0, k -> labBottom = k), 10);
        nightCb = check("Modo noite");
        alertCb = check("Alerta");
        c2.addView(row(nightCb, alertCb));
        CheckBox keepCb = check("Repetir a cada 1 s");
        keepCb.setChecked(true);
        keepCb.setOnCheckedChangeListener((b, on) -> core.pod.setKeepaliveOn(on));
        autoCb = check("Avançar a cada 3 s");
        autoCb.setOnCheckedChangeListener((b, on) -> { h.removeCallbacks(autoRun); if (on) h.postDelayed(autoRun, 3000); });
        c2.addView(row(keepCb, autoCb));
        c2.addView(row(btn("Enviar pro pod", true, v -> labSend())));
        noteEt = edit("O que apareceu no pod?", InputType.TYPE_CLASS_TEXT, null);
        c2.addView(row(noteEt, btn("Anotar", false, v -> {
            String t = noteEt.getText().toString().trim();
            if (t.isEmpty()) return;
            notes.put(cur, t);
            noteEt.setText("");
            saveNotes();
            refreshCur();
        })));
        notesView = text(notesText(), 12, TXT, false);
        notesView.setTypeface(Typeface.MONOSPACE);
        notesView.setPadding(0, dp(8), 0, 0);
        c2.addView(notesView);
        c2.addView(row(btn("Copiar anotações", false, v -> copy("anotações", notesText())),
                btn("Limpar", false, v -> { notes.clear(); saveNotes(); refreshCur(); })));

        tab.addView(caption("TELAS ESCONDIDAS (0x40)"));
        LinearLayout c3 = card(tab);
        ctrlTxt = text("sub 1", 17, ACC, true);
        ctrlTxt.setGravity(Gravity.CENTER);
        LinearLayout cr = new LinearLayout(this);
        cr.setGravity(Gravity.CENTER_VERTICAL);
        cr.addView(btn("−", false, v -> ctrlStep(-1)), new LinearLayout.LayoutParams(dp(58), dp(52)));
        cr.addView(ctrlTxt, new LinearLayout.LayoutParams(0, -2, 1f));
        cr.addView(btn("+", false, v -> ctrlStep(1)), new LinearLayout.LayoutParams(dp(58), dp(52)));
        c3.addView(cr);

        tab.addView(caption("HEX MANUAL"));
        LinearLayout c4 = card(tab);
        rawEt = edit("até 18 bytes, ex: 10 11 15 00 C8 01", InputType.TYPE_CLASS_TEXT, null);
        c4.addView(rawEt);
        c4.addView(row(btn("Enviar (CRC automático)", false, v -> {
            try {
                byte[] f = Proto.frameRaw(Proto.parseHex(rawEt.getText().toString()));
                core.pod.setKeepalive(f);
                core.pod.send(f, "manual");
            } catch (Exception ex) { core.onLog("hex inválido", Pod.LOG_ERR); }
        })));

        tab.addView(caption("LOG"));
        LinearLayout c5 = card(tab);
        logView = text("", 11, TXT, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackground(round(0xFF07090D, 0, 12));
        logView.setMinHeight(dp(180));
        c5.addView(logView);
        c5.addView(row(btn("Copiar log", false, v -> copy("log", logBuf.toString())),
                btn("Limpar", false, v -> { logBuf.clear(); logLines = 0; logView.setText(""); core.logs.clear(); })));
        refreshCur();
        return tab;
    }

    private CheckBox check(String s) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(TXT);
        c.setButtonTintList(ColorStateList.valueOf(ACC));
        return c;
    }

    private Spinner spinner(List<String> items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setBackground(round(SURF2, 0, 14));
        s.setPadding(dp(10), dp(10), dp(10), dp(10));
        return s;
    }
}
