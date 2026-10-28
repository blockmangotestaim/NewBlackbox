package com.memscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ScannerActivity extends Activity {
    /** Main activity of the BlackBox demo app (the "virtual space" screen). Edit if yours differs. */
    private static final String VIRTUAL_SPACE_ACTIVITY = "top.niunaijun.blackboxa.view.main.MainActivity";

    private static final int MAX_ROWS = 1000;
    private static final String[] TYPES = {
            "Group  (e.g. 2:0 1:0-255 w:35)", "1 byte", "2 bytes (word)", "4 bytes (dword)",
            "8 bytes (qword)", "Float", "Double"};
    private static final String[] PREFIX = {"", "1:", "2:", "4:", "8:", "f:", "e:"};
    private static final String[] REGIONS = {"Writable memory (fast)", "All readable memory"};

    private int targetPid = -1;
    private TextView tvTarget, tvStatus;
    private EditText etValue;
    private Spinner spType, spRegion;
    private CheckBox cbAligned;
    private Button btnFirst, btnNext, btnNew;
    private ProgressBar bar;
    private ArrayAdapter<String> adapter;
    private final ArrayList<String> rows = new ArrayList<>();

    private volatile boolean cancel;
    private boolean busy;
    private MemScanner.Result last;
    private List<MemScanner.Region> lastRegions;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(8);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1B1B1F);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("MemScan  -  read-only memory scanner", 18, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        tvTarget = text("Target: (none selected)", 14, 0xFFFFB74D);
        tvTarget.setPadding(0, dp(4), 0, dp(4));
        root.addView(tvTarget);

        LinearLayout r1 = row();
        r1.addView(button("Virtual space", v -> openVirtualSpace()), weight());
        r1.addView(button("Select target", v -> pickTarget()), weight());
        root.addView(r1);

        LinearLayout r2 = row();
        spType = new Spinner(this);
        spType.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, TYPES));
        spRegion = new Spinner(this);
        spRegion.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, REGIONS));
        r2.addView(spType, weight());
        r2.addView(spRegion, weight());
        root.addView(r2);

        cbAligned = new CheckBox(this);
        cbAligned.setText("Fast scan (aligned)  -  untick for group values that may sit at odd addresses");
        cbAligned.setTextColor(0xFFBBBBBB);
        cbAligned.setTextSize(12);
        cbAligned.setChecked(true);
        root.addView(cbAligned);

        etValue = new EditText(this);
        etValue.setHint("value, range (1-50) or group (2:0 2:0 1:0-255 w:35)");
        etValue.setHintTextColor(0xFF777777);
        etValue.setTextColor(Color.WHITE);
        etValue.setTypeface(Typeface.MONOSPACE);
        etValue.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(etValue);

        LinearLayout r3 = row();
        btnFirst = button("First scan", v -> onFirst());
        btnNext = button("Next scan", v -> onNext());
        btnNew = button("New", v -> onNew());
        r3.addView(btnFirst, weight());
        r3.addView(btnNext, weight());
        r3.addView(btnNew, weight());
        root.addView(r3);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        tvStatus = text("Pick a target, choose a type, type a value, press First scan.", 14, 0xFF80CBC4);
        tvStatus.setPadding(0, dp(4), 0, dp(4));
        root.addView(tvStatus);

        TextView head = text("ADDRESS      VALUE      REGION", 11, 0xFF90A4AE);
        head.setTypeface(Typeface.MONOSPACE);
        root.addView(head);

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextSize(12);
                t.setTextColor(0xFFE0E0E0);
                t.setPadding(dp(4), dp(2), dp(4), dp(2));
                return t;
            }
        };
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    // ------------------------------------------------------------------ actions

    private void openVirtualSpace() {
        try {
            Intent i = new Intent();
            i.setClassName(getPackageName(), VIRTUAL_SPACE_ACTIVITY);
            startActivity(i);
        } catch (Exception e) {
            toast("Set VIRTUAL_SPACE_ACTIVITY in ScannerActivity.java to your BlackBox main activity.");
        }
    }

    private void pickTarget() {
        final List<MemScanner.ProcInfo> ps = MemScanner.listProcesses();
        if (ps.isEmpty()) {
            toast("No other process of this app is running. Open the virtual space and launch the game first.");
            return;
        }
        String[] labels = new String[ps.size()];
        for (int i = 0; i < labels.length; i++) {
            MemScanner.ProcInfo p = ps.get(i);
            labels[i] = p.name + "\npid " + p.pid + "  -  " + (p.rssKb / 1024) + " MB RAM";
        }
        new AlertDialog.Builder(this)
                .setTitle("Select target (the game is usually the biggest)")
                .setItems(labels, (d, i) -> {
                    targetPid = ps.get(i).pid;
                    tvTarget.setText("Target: " + ps.get(i).name + "  (pid " + targetPid + ")");
                    onNew();
                }).show();
    }

    private void onNew() {
        last = null;
        lastRegions = null;
        rows.clear();
        adapter.notifyDataSetChanged();
        bar.setProgress(0);
        tvStatus.setText("Ready.");
    }

    private GroupPattern buildPattern() {
        String t = etValue.getText().toString().trim();
        if (t.isEmpty()) throw new IllegalArgumentException("Enter a value to search for.");
        int ti = spType.getSelectedItemPosition();
        if (ti > 0) t = PREFIX[ti] + t;
        return GroupPattern.parse(t);
    }

    private void onFirst() {
        if (busy) { cancel = true; return; }
        if (targetPid < 0) { toast("Select a target first."); return; }
        try { start(true, buildPattern()); }
        catch (IllegalArgumentException e) { tvStatus.setText(e.getMessage()); }
    }

    private void onNext() {
        if (busy) return;
        if (last == null) { toast("Do a First scan first."); return; }
        try { start(false, buildPattern()); }
        catch (IllegalArgumentException e) { tvStatus.setText(e.getMessage()); }
    }

    private void start(final boolean first, final GroupPattern pat) {
        busy = true;
        cancel = false;
        btnFirst.setText("Stop");
        final int pid = targetPid;
        final boolean writable = spRegion.getSelectedItemPosition() == 0;
        final boolean aligned = cbAligned.isChecked();
        final MemScanner.Result prev = last;
        final List<MemScanner.Region> prevRegions = lastRegions;

        final MemScanner.Callback cb = new MemScanner.Callback() {
            @Override public void progress(final int pct, final long found) {
                runOnUiThread(() -> {
                    bar.setProgress(pct);
                    tvStatus.setText("Scanning... " + pct + "%   found " + fmt(found));
                });
            }
            @Override public boolean cancelled() { return cancel; }
        };

        new Thread(() -> {
            try {
                final List<MemScanner.Region> regs = first ? MemScanner.readMaps(pid, writable) : prevRegions;
                final MemScanner.Result r = first
                        ? MemScanner.firstScan(pid, regs, pat, aligned, cb)
                        : MemScanner.nextScan(pid, prev.addrs, prev.stored, pat, cb);
                final List<String> out = new ArrayList<>();
                try (MemScanner.Mem mem = new MemScanner.Mem(pid)) {
                    int show = Math.min(r.stored, MAX_ROWS);
                    for (int i = 0; i < show; i++) {
                        long a = r.addrs[i];
                        byte[] v = mem.read(a, pat.length);
                        out.add(String.format(Locale.ROOT, "%010X   %s   %s", a,
                                v == null ? "??" : pat.format(v), regionOf(regs, a)));
                    }
                }
                runOnUiThread(() -> {
                    last = r;
                    lastRegions = regs;
                    rows.clear();
                    rows.addAll(out);
                    adapter.notifyDataSetChanged();
                    bar.setProgress(100);
                    StringBuilder s = new StringBuilder();
                    s.append(cancel ? "Stopped.  " : "Done.  ");
                    s.append("Found ").append(fmt(r.total)).append(" match(es)");
                    s.append("  -  ").append(fmt(r.bytes / (1024 * 1024))).append(" MB in ")
                            .append(String.format(Locale.ROOT, "%.1f", r.ms / 1000.0)).append(" s");
                    if (r.total > r.stored) s.append("\nOnly the first ").append(fmt(r.stored))
                            .append(" are kept for Next scan - narrow your search.");
                    if (r.stored > MAX_ROWS) s.append("\nShowing first ").append(MAX_ROWS).append(".");
                    if (first && r.failedRegions > 0) s.append("\n").append(r.failedRegions).append(" region(s) unreadable.");
                    if (first && r.regions == 0) s.append("\nNo region could be read - is the target a virtual-space process?");
                    tvStatus.setText(s.toString());
                    finish(false);
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    String m = String.valueOf(e.getMessage());
                    if (m.contains("EACCES") || m.contains("Permission") || m.contains("ENOENT"))
                        m += "\nCannot open that process's memory. It must be a process started inside this app's virtual space (same UID), and still running.";
                    tvStatus.setText("Error: " + m);
                    finish(true);
                });
            }
        }).start();
    }

    private void finish(boolean error) {
        busy = false;
        cancel = false;
        btnFirst.setText("First scan");
    }

    // ------------------------------------------------------------------ helpers

    private static String regionOf(List<MemScanner.Region> regs, long a) {
        if (regs == null) return "?";
        for (MemScanner.Region r : regs) {
            if (a >= r.start && a < r.end) {
                String p = r.path;
                if (p == null || p.isEmpty()) return "anon " + r.perms;
                int s = p.lastIndexOf('/');
                return (s >= 0 ? p.substring(s + 1) : p) + " " + r.perms;
            }
        }
        return "?";
    }

    private static String fmt(long n) { return NumberFormat.getInstance().format(n); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xFF37474F);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        return p;
    }
}
