package com.example.arrows.ui;

import com.example.arrows.model.Direction;
import com.example.arrows.signals.GameService;
import com.example.arrows.signals.GameSnapshot;
import com.example.arrows.signals.GameSnapshot.ArrowData;
import com.example.arrows.signals.MoveResult;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.signals.Signal;
import com.vaadin.flow.signals.shared.SharedValueSignal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Route("")
public class GameView extends VerticalLayout {

    private final GameService gameService;
    private final SharedValueSignal<GameSnapshot> gameSignal;

    private Div boardContainer;
    private Span movesLabel;
    private Span heartsLabel;
    private Span levelTitle;
    private Span arrowsRemaining;
    private Button restartBtn;
    private Button nextLevelBtn;
    private Dialog currentDialog;

    // Tracks whether THIS session triggered the win/loss
    private boolean iTriggeredEnd = false;

    // SVG arrow group elements keyed by arrow ID, for animation targeting
    private final Map<String, Element> arrowElements = new HashMap<>();

    // Animation timing — prevents re-render from stomping on in-progress animations

    private long animatingUntil = 0;

    // Tracks last seen move timestamp for spectator animation detection
    private long lastSeenMoveTs = 0;

    // Board geometry constants
    private static final int CELL_PAD = 2;
    private static final int BOARD_PAD = 6;

    public GameView(GameService gameService) {
        this.gameService = gameService;
        this.gameSignal = gameService.gameState();

        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setPadding(true);
        addClassName("game-view");

        buildUI();
        setupSignalBindings();
    }

    private void buildUI() {
        removeAll();

        // Compact header block — constrained to board width
        VerticalLayout header = new VerticalLayout();
        header.setPadding(false);
        header.setSpacing(false);
        header.addClassName("header");

        // Row 1: title + actions
        HorizontalLayout titleRow = new HorizontalLayout();
        titleRow.setWidthFull();
        titleRow.setAlignItems(Alignment.BASELINE);
        titleRow.setJustifyContentMode(JustifyContentMode.BETWEEN);
        titleRow.addClassName("title-row");

        Span title = new Span("Arrows Puzzle");
        title.addClassName("game-title");

        HorizontalLayout actions = new HorizontalLayout();
        actions.setAlignItems(Alignment.CENTER);
        actions.addClassName("header-actions");

        restartBtn = new Button("Restart", e -> {
            animatingUntil = 0;
            gameService.restartLevel();
            closeDialog();
        });
        styleButton(restartBtn, false);

        Button customBtn = new Button("Custom Level", e ->
                getUI().ifPresent(ui -> ui.navigate(SvgLevelGeneratorView.class)));
        customBtn.addClassName("btn-green");

        actions.add(restartBtn, customBtn);
        titleRow.add(title, actions);
        header.add(titleRow);

        // Row 2: level name (centered)
        levelTitle = new Span();
        levelTitle.addClassName("level-title");
        header.add(levelTitle);

        // Row 3: stacked stats (centered)
        HorizontalLayout stats = new HorizontalLayout();
        stats.setAlignItems(Alignment.END);
        stats.setJustifyContentMode(JustifyContentMode.CENTER);
        stats.setWidthFull();
        stats.addClassName("stats-group");

        heartsLabel = new Span();
        heartsLabel.addClassName("hearts-label");

        movesLabel = new Span();
        movesLabel.addClassName("stat-value");

        arrowsRemaining = new Span();
        arrowsRemaining.addClassName("stat-value");

        stats.add(
                buildStat("Lives", heartsLabel),
                buildStat("Moves", movesLabel),
                buildStat("Arrows", arrowsRemaining)
        );
        header.add(stats);

        add(header);

        boardContainer = new Div();
        boardContainer.addClassName("board-container");
        add(boardContainer);

        nextLevelBtn = new Button("Next Level", e -> {
            animatingUntil = 0;
            gameService.nextLevel();
            closeDialog();
        });
        styleButton(nextLevelBtn, true);
        nextLevelBtn.setVisible(false);
        add(nextLevelBtn);
    }

    // ====== Reactive signal bindings ======

    private void setupSignalBindings() {
        // Text bindings — update automatically across all sessions
        levelTitle.bindText(gameSignal.map(s ->
                "Level " + s.levelId() + ": " + s.levelTitle()
                        + " (" + s.gridSize() + "x" + s.gridSize() + ")"));

        movesLabel.bindText(gameSignal.map(s -> String.valueOf(s.moves())));

        heartsLabel.bindText(gameSignal.map(s -> heartsDisplay(s.hearts())));

        arrowsRemaining.bindText(gameSignal.map(s -> {
            long rem = s.arrows().stream().filter(a -> !a.exited()).count();
            return rem + "/" + s.arrows().size();
        }));

        // Board rendering effect — handles SVG and cross-session animations
        Signal.effect(this, ctx -> {
            GameSnapshot snap = gameSignal.get();
            if (snap == null) return;

            long now = System.currentTimeMillis();
            GameSnapshot.MoveEvent move = snap.lastMove();
            boolean isNewMove = move != null && move.timestamp() > lastSeenMoveTs;

            // While local animation plays, don't consume unseen moves — let them
            // be picked up after animation finishes (in scheduleRerender)
            if (now < animatingUntil) return;

            // Animate unseen moves (from other sessions or missed during animation)
            if (isNewMove && !ctx.isInitialRun()) {
                lastSeenMoveTs = move.timestamp();
                renderSvgBoard(snap, move.arrowId(), move.result());

                snap.arrows().stream()
                        .filter(a -> a.id().equals(move.arrowId()))
                        .findFirst()
                        .ifPresent(arrow ->
                                playAnimation(arrow, move.result(), snap));
                return;
            }

            // Normal render (initial load, level change, post-animation)
            if (move != null) lastSeenMoveTs = move.timestamp();
            renderSvgBoard(snap, null, null);
            checkEndState(snap);
        });
    }

    // ====== SVG Board Rendering ======

    private int cellSize(int gridSize) {
        // Adaptive: small grids get bigger cells, large grids pack tightly
        int target = gridSize <= 8 ? 420 : 520;
        int available = target - BOARD_PAD * 2 - (gridSize - 1) * CELL_PAD;
        return Math.min(56, Math.max(14, available / gridSize));
    }

    private int boardPixels(int gridSize) {
        int cs = cellSize(gridSize);
        return BOARD_PAD * 2 + gridSize * cs + (gridSize - 1) * CELL_PAD;
    }

    private void renderSvgBoard(GameSnapshot snap, String animateId, MoveResult animateResult) {
        boardContainer.removeAll();
        arrowElements.clear();

        int gs = snap.gridSize();
        int cs = cellSize(gs);
        int totalSize = boardPixels(gs);

        Element svg = new Element("svg");
        svg.setAttribute("viewBox", "0 0 " + totalSize + " " + totalSize);
        svg.setAttribute("width", String.valueOf(totalSize));
        svg.setAttribute("height", String.valueOf(totalSize));
        svg.setAttribute("class", "board-svg");

        // Defs
        Element defs = new Element("defs");

        // Drop shadow
        Element filter = new Element("filter");
        filter.setAttribute("id", "shadow");
        filter.setAttribute("x", "-20%");
        filter.setAttribute("y", "-20%");
        filter.setAttribute("width", "140%");
        filter.setAttribute("height", "140%");
        Element feDropShadow = new Element("feDropShadow");
        feDropShadow.setAttribute("dx", "0");
        feDropShadow.setAttribute("dy", "2");
        feDropShadow.setAttribute("stdDeviation", "3");
        feDropShadow.setAttribute("flood-color", "rgba(0,0,0,0.5)");
        filter.appendChild(feDropShadow);
        defs.appendChild(filter);

        // Top-lit shine gradient (vertical)
        Element shineV = new Element("linearGradient");
        shineV.setAttribute("id", "shine-v");
        shineV.setAttribute("x1", "0"); shineV.setAttribute("y1", "0");
        shineV.setAttribute("x2", "0"); shineV.setAttribute("y2", "1");
        appendStop(shineV, "0%", "white", "0.30");
        appendStop(shineV, "35%", "white", "0.08");
        appendStop(shineV, "100%", "black", "0.12");
        defs.appendChild(shineV);

        // Left-lit shine gradient (horizontal)
        Element shineH = new Element("linearGradient");
        shineH.setAttribute("id", "shine-h");
        shineH.setAttribute("x1", "0"); shineH.setAttribute("y1", "0");
        shineH.setAttribute("x2", "1"); shineH.setAttribute("y2", "0");
        appendStop(shineH, "0%", "white", "0.30");
        appendStop(shineH, "35%", "white", "0.08");
        appendStop(shineH, "100%", "black", "0.12");
        defs.appendChild(shineH);

        svg.appendChild(defs);

        // Board background
        Element boardBg = new Element("rect");
        boardBg.setAttribute("x", "0");
        boardBg.setAttribute("y", "0");
        boardBg.setAttribute("width", String.valueOf(totalSize));
        boardBg.setAttribute("height", String.valueOf(totalSize));
        boardBg.setAttribute("rx", "12");
        boardBg.setAttribute("fill", "#0f0f23");
        svg.appendChild(boardBg);

        // Grid cells — only render playable cells
        boolean[][] mask = snap.playableMask();
        for (int r = 0; r < gs; r++) {
            for (int c = 0; c < gs; c++) {
                if (mask != null && !mask[r][c]) continue;
                Element cell = new Element("rect");
                cell.setAttribute("x", String.valueOf(cellX(c, cs)));
                cell.setAttribute("y", String.valueOf(cellY(r, cs)));
                cell.setAttribute("width", String.valueOf(cs));
                cell.setAttribute("height", String.valueOf(cs));
                cell.setAttribute("rx", "6");
                cell.setAttribute("fill", "#1a1a2e");
                svg.appendChild(cell);
            }
        }

        // Arrow groups
        for (ArrowData arrow : snap.arrows()) {
            boolean isAnimatingExit = arrow.exited()
                    && arrow.id().equals(animateId)
                    && (animateResult == MoveResult.SUCCESS || animateResult == MoveResult.WIN);

            if (arrow.exited() && !isAnimatingExit) continue;

            boolean isFailed = arrow.id().equals(animateId)
                    && (animateResult == MoveResult.COLLISION || animateResult == MoveResult.LOST);

            Element group = createArrowGroup(arrow, cs, isFailed);
            svg.appendChild(group);
            arrowElements.put(arrow.id(), group);
        }

        boardContainer.getElement().appendChild(svg);
    }

    private int cellX(int col, int cs) {
        return BOARD_PAD + col * (cs + CELL_PAD);
    }

    private int cellY(int row, int cs) {
        return BOARD_PAD + row * (cs + CELL_PAD);
    }

    private Element createArrowGroup(ArrowData arrow, int cs, boolean isFailed) {
        Element g = new Element("g");
        g.setAttribute("data-arrow-id", arrow.id());
        g.setAttribute("class", "arrow-group");

        String pathD = buildArrowPath(arrow, cs);
        String color = isFailed ? "#c62828" : arrow.color();

        // Main colored body
        Element body = new Element("path");
        body.setAttribute("d", pathD);
        body.setAttribute("fill", color);
        body.setAttribute("filter", "url(#shadow)");
        body.setAttribute("class", "arrow-body");
        g.appendChild(body);

        // Shine overlay for 3D effect
        boolean horiz = (arrow.headDirection() == Direction.LEFT
                || arrow.headDirection() == Direction.RIGHT);
        Element shine = new Element("path");
        shine.setAttribute("d", pathD);
        shine.setAttribute("fill", "url(#shine-" + (horiz ? "v" : "h") + ")");
        g.appendChild(shine);

        g.addEventListener("click", event -> handleArrowClick(arrow.id()));
        return g;
    }

    /**
     * Builds a uniform uni-body SVG path for an arrow.
     * The chevron head always points in headDirection, with a smooth
     * turn from the body direction if they differ.
     */
    private String buildArrowPath(ArrowData arrow, int cs) {
        int[][] segs = arrow.segments();
        int n = segs.length;
        Direction headDir = arrow.headDirection();

        double bh = Math.max(2.0, cs * 0.09);   // tiny waist, min 2px visible
        double hh = bh + Math.max(3.0, cs * 0.20); // wide chevron head
        double margin = Math.max(1.5, cs * 0.08);   // inset from cell edge

        // Pixel centers of each segment cell
        double[] px = new double[n], py = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = cellX(segs[i][1], cs) + cs / 2.0;
            py[i] = cellY(segs[i][0], cs) + cs / 2.0;
        }

        // Head direction in pixel space
        double hdx = headDir.dCol(), hdy = headDir.dRow();

        // Edge directions between consecutive centers (normalized)
        double[][] ed = new double[Math.max(n - 1, 1)][2];
        if (n == 1) {
            ed[0] = new double[]{hdx, hdy};
        } else {
            for (int i = 0; i < n - 1; i++) {
                double dx = px[i + 1] - px[i], dy = py[i + 1] - py[i];
                double len = Math.sqrt(dx * dx + dy * dy);
                ed[i] = new double[]{dx / len, dy / len};
            }
        }

        // Collect turn indices (where direction changes) — skip straight-run midpoints
        List<Integer> turns = new ArrayList<>();
        for (int j = 1; j < n - 1; j++) {
            if (Math.abs(ed[j - 1][0] - ed[j][0]) > 0.01
                    || Math.abs(ed[j - 1][1] - ed[j][1]) > 0.01) {
                turns.add(j);
            }
        }

        // leftPerp of (dx,dy) in screen coords: (dy, -dx)
        List<double[]> left = new ArrayList<>();
        List<double[]> right = new ArrayList<>();

        // --- Tail: extend back from center[0] ---
        double[] d0 = ed[0];
        double lp0x = d0[1], lp0y = -d0[0];
        double tEx = cs / 2.0 - margin;
        double tx = px[0] - d0[0] * tEx, ty = py[0] - d0[1] * tEx;
        left.add(new double[]{tx + bh * lp0x, ty + bh * lp0y});
        right.add(new double[]{tx - bh * lp0x, ty - bh * lp0y});

        // --- Turn points only (miter joins) ---
        for (int j : turns) {
            double lpInX = ed[j - 1][1], lpInY = -ed[j - 1][0];
            double lpOutX = ed[j][1], lpOutY = -ed[j][0];
            left.add(new double[]{
                    px[j] + bh * (lpInX + lpOutX),
                    py[j] + bh * (lpInY + lpOutY)});
            right.add(new double[]{
                    px[j] - bh * (lpInX + lpOutX),
                    py[j] - bh * (lpInY + lpOutY)});
        }

        // --- Turn into headDir if body approach direction differs ---
        // Uses square corners (two points per side) instead of a diagonal miter,
        // so the last cell has a clean straight run in headDirection before the chevron.
        double[] dLast = (n > 1) ? ed[n - 2] : ed[0];
        boolean headTurn = Math.abs(dLast[0] - hdx) > 0.01 || Math.abs(dLast[1] - hdy) > 0.01;
        if (headTurn && n > 1) {
            double liX = dLast[1], liY = -dLast[0]; // leftPerp of incoming
            double loX = hdy, loY = -hdx;           // leftPerp of headDir
            // Left: incoming wall end -> square corner -> outgoing wall
            left.add(new double[]{px[n - 1] + bh * liX, py[n - 1] + bh * liY});
            left.add(new double[]{px[n - 1] + bh * liX + bh * loX, py[n - 1] + bh * liY + bh * loY});
            // Right: incoming wall end -> square corner -> outgoing wall
            right.add(new double[]{px[n - 1] - bh * liX, py[n - 1] - bh * liY});
            right.add(new double[]{px[n - 1] - bh * liX - bh * loX, py[n - 1] - bh * liY - bh * loY});
        }

        // --- Chevron base (always oriented in headDirection) ---
        double hlpx = hdy, hlpy = -hdx;
        double cbx = px[n - 1] + hdx * cs * 0.1;
        double cby = py[n - 1] + hdy * cs * 0.1;
        left.add(new double[]{cbx + bh * hlpx, cby + bh * hlpy});
        right.add(new double[]{cbx - bh * hlpx, cby - bh * hlpy});

        // --- Chevron ---
        double tipX = px[n - 1] + hdx * (cs / 2.0 - margin);
        double tipY = py[n - 1] + hdy * (cs / 2.0 - margin);
        double cwLx = cbx + hh * hlpx, cwLy = cby + hh * hlpy;
        double cwRx = cbx - hh * hlpx, cwRy = cby - hh * hlpy;

        // --- Assemble SVG path ---
        StringBuilder sb = new StringBuilder();

        // Left outline (tail -> head)
        sb.append("M").append(f(left.get(0)[0])).append(",").append(f(left.get(0)[1]));
        for (int i = 1; i < left.size(); i++) {
            sb.append(" L").append(f(left.get(i)[0])).append(",").append(f(left.get(i)[1]));
        }

        // Chevron: left wing -> tip -> right wing
        sb.append(" L").append(f(cwLx)).append(",").append(f(cwLy));
        sb.append(" L").append(f(tipX)).append(",").append(f(tipY));
        sb.append(" L").append(f(cwRx)).append(",").append(f(cwRy));

        // Right outline (head -> tail)
        for (int i = right.size() - 1; i >= 0; i--) {
            sb.append(" L").append(f(right.get(i)[0])).append(",").append(f(right.get(i)[1]));
        }

        // Rounded tail cap (semicircular arc)
        sb.append(" A").append(f(bh)).append(",").append(f(bh));
        sb.append(" 0 0 1 ");
        sb.append(f(left.get(0)[0])).append(",").append(f(left.get(0)[1]));
        sb.append(" Z");

        return sb.toString();
    }

    private static String f(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    // ====== Animation ======

    /**
     * Plays the snake-unravel exit animation on an arrow element.
     * The head leads in headDirection, body follows behind like a train.
     * Uses requestAnimationFrame for smooth frame-by-frame path updates.
     * Returns the animation duration in ms.
     */
    private int playExitUnravel(Element el, ArrowData arrow, int cs, int gs) {
        int[][] segs = arrow.segments();
        int segCount = segs.length;
        Direction dir = arrow.headDirection();

        // Build pixel coordinate arrays as JSON
        StringBuilder centersX = new StringBuilder("[");
        StringBuilder centersY = new StringBuilder("[");
        for (int i = 0; i < segCount; i++) {
            if (i > 0) { centersX.append(","); centersY.append(","); }
            centersX.append(f(cellX(segs[i][1], cs) + cs / 2.0));
            centersY.append(f(cellY(segs[i][0], cs) + cs / 2.0));
        }
        centersX.append("]");
        centersY.append("]");

        double bodyHalf = Math.max(2.0, cs * 0.09);
        double headHalf = bodyHalf + Math.max(3.0, cs * 0.20);
        double margin = Math.max(1.5, cs * 0.08);
        int cellStep = cs + CELL_PAD;

        // Distance from head to grid edge in head direction
        int[] head = segs[segCount - 1];
        int distToEdge = switch (dir) {
            case RIGHT -> gs - 1 - head[1];
            case LEFT -> head[1];
            case DOWN -> gs - 1 - head[0];
            case UP -> head[0];
        };

        int totalSteps = distToEdge + segCount + 2;  // +2 extra to ensure full exit
        int duration = Math.max(300, totalSteps * 70); // linear speed: 70ms per cell

        el.executeJs(
            "window._animateExit(this," +
            centersX + "," + centersY + "," +
            dir.dCol() + "," + dir.dRow() + "," +
            cellStep + "," + f(bodyHalf) + "," + f(headHalf) + "," + f(margin) + "," + cs + "," +
            totalSteps + "," + duration + ")"
        );

        return duration;
    }

    /**
     * Play a bump animation: arrow slides forward toward collision, then bounces back.
     * The entire arrow group is translated, preserving its shape and arrowhead.
     * Returns the animation duration in ms.
     */
    private int playBumpAnimation(Element el, ArrowData arrow, int cs, int bumpSteps) {
        int segCount = arrow.segments().length;
        Direction dir = arrow.headDirection();
        int cellStep = cs + CELL_PAD;

        double bumpDist = Math.max(0.5, bumpSteps + 0.5);
        int duration = Math.max(500, Math.min(1000, (int)(bumpDist * 80) + segCount * 30));

        el.executeJs(
            "window._animateBump(this," +
            dir.dCol() + "," + dir.dRow() + "," +
            cellStep + "," + f(bumpDist) + "," + duration + ")"
        );

        return duration;
    }

    /**
     * Plays move animation on an arrow element (for spectator/remote moves).
     */
    private void playAnimation(ArrowData arrow, MoveResult result, GameSnapshot snap) {
        Element el = arrowElements.get(arrow.id());
        if (el == null) return;

        int gs = snap.gridSize();
        int cs = cellSize(gs);
        long now = System.currentTimeMillis();
        Direction dir = arrow.headDirection();

        if (result == MoveResult.SUCCESS || result == MoveResult.WIN) {
            int duration = playExitUnravel(el, arrow, cs, gs);
            animatingUntil = now + duration;
            scheduleRerender(duration + 50);

        } else if (result == MoveResult.COLLISION || result == MoveResult.LOST) {
            GameSnapshot.MoveEvent move = snap.lastMove();
            int bumpSteps = move != null ? move.collisionSteps() : 1;

            int duration = playBumpAnimation(el, arrow, cs, bumpSteps);
            animatingUntil = now + duration;
            scheduleRerender(duration + 50);
        }
    }

    // ====== Click handler ======

    private void handleArrowClick(String arrowId) {
        GameSnapshot snap = gameSignal.peek();
        if (snap == null || snap.gameWon() || snap.gameLost()) return;

        ArrowData arrow = snap.arrows().stream()
                .filter(a -> a.id().equals(arrowId) && !a.exited())
                .findFirst().orElse(null);
        if (arrow == null) return;

        Direction dir = arrow.headDirection();

        // Conservative animation lock — will be tightened once we know the result
        long now = System.currentTimeMillis();
        animatingUntil = now + 1000;

        MoveResult result = gameService.moveArrow(arrowId);

        // Mark our own move as "seen" so the effect doesn't re-animate it
        GameSnapshot postSnap = gameSignal.peek();
        if (postSnap != null && postSnap.lastMove() != null) {
            lastSeenMoveTs = postSnap.lastMove().timestamp();
        }

        if (result == MoveResult.GAME_OVER || result == MoveResult.ALREADY_EXITED) {
            animatingUntil = 0;
            return;
        }

        if (result == MoveResult.WIN || result == MoveResult.LOST) {
            iTriggeredEnd = true;
        }

        Element el = arrowElements.get(arrowId);
        if (el == null) {
            animatingUntil = 0;
            return;
        }

        int gs = snap.gridSize();
        int cs = cellSize(gs);

        if (result == MoveResult.SUCCESS || result == MoveResult.WIN) {
            int duration = playExitUnravel(el, arrow, cs, gs);
            animatingUntil = now + duration;
            scheduleRerender(duration + 50);

        } else if (result == MoveResult.COLLISION || result == MoveResult.LOST) {
            GameSnapshot latestSnap = gameSignal.peek();
            GameSnapshot.MoveEvent move = latestSnap != null ? latestSnap.lastMove() : null;
            int bumpSteps = move != null ? move.collisionSteps() : 1;

            int duration = playBumpAnimation(el, arrow, cs, bumpSteps);
            animatingUntil = now + duration;
            scheduleRerender(duration + 50);
        }
    }

    // ====== Post-animation re-render ======

    private void scheduleRerender(int delayMs) {
        getUI().ifPresent(ui ->
            Thread.startVirtualThread(() -> {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
                ui.access(() -> {
                    animatingUntil = 0;
                    GameSnapshot snap = gameSignal.peek();
                    if (snap == null) return;

                    // Check if a move happened while we were animating
                    GameSnapshot.MoveEvent move = snap.lastMove();
                    if (move != null && move.timestamp() > lastSeenMoveTs) {
                        lastSeenMoveTs = move.timestamp();
                        renderSvgBoard(snap, move.arrowId(), move.result());
                        snap.arrows().stream()
                                .filter(a -> a.id().equals(move.arrowId()))
                                .findFirst()
                                .ifPresent(arrow ->
                                        playAnimation(arrow, move.result(), snap));
                        return;
                    }

                    renderSvgBoard(snap, null, null);
                    checkEndState(snap);
                });
            })
        );
    }

    // ====== End state (dialogs) ======

    private void checkEndState(GameSnapshot snap) {
        if (System.currentTimeMillis() < animatingUntil) return;

        boolean won = snap.gameWon();
        boolean lost = snap.gameLost();
        nextLevelBtn.setVisible(won);

        if (won && currentDialog == null) {
            showWinDialog(iTriggeredEnd, snap);
            iTriggeredEnd = false;
        } else if (lost && currentDialog == null) {
            showLoseDialog(iTriggeredEnd);
            iTriggeredEnd = false;
        } else if (!won && !lost && currentDialog != null) {
            closeDialog();
        }
    }

    private void showWinDialog(boolean myMove, GameSnapshot snap) {
        closeDialog();
        Dialog dialog = new Dialog();
        dialog.setCloseOnOutsideClick(false);
        dialog.setWidth("380px");

        VerticalLayout content = new VerticalLayout();
        content.setAlignItems(Alignment.CENTER);
        content.setPadding(true);
        content.addClassName("dialog-content");

        H2 winTitle = new H2(myMove ? "You cleared the last arrow!" : "Level Complete!");
        winTitle.addClassName("win-title");

        Span roleInfo = new Span(myMove
                ? "You made the winning move!"
                : "Another player cleared the board!");
        roleInfo.addClassName("role-info");
        roleInfo.addClassName(myMove ? "role-info-my-win" : "role-info-other");

        Span movesInfo = new Span("Completed in " + snap.moves() + " moves");
        movesInfo.addClassName("win-moves-info");

        Span levelsInfo = new Span(snap.levelsCompleted() + " levels completed together");
        levelsInfo.addClassName("win-levels-info");

        Button nextBtn = new Button("Next Level", e -> {
            animatingUntil = 0;
            gameService.nextLevel();
            closeDialog();
        });
        styleButton(nextBtn, true);
        nextBtn.addClassName("dialog-btn-top");

        content.add(winTitle, roleInfo, movesInfo, levelsInfo, nextBtn);
        dialog.add(content);
        dialog.open();
        currentDialog = dialog;
    }

    private void showLoseDialog(boolean myMove) {
        closeDialog();
        Dialog dialog = new Dialog();
        dialog.setCloseOnOutsideClick(false);
        dialog.setWidth("380px");

        VerticalLayout content = new VerticalLayout();
        content.setAlignItems(Alignment.CENTER);
        content.setPadding(true);
        content.addClassName("dialog-content");

        H2 loseTitle = new H2("Out of Hearts!");
        loseTitle.addClassName("lose-title");

        Span roleInfo = new Span(myMove
                ? "Your move used the last heart..."
                : "Another player used the last heart!");
        roleInfo.addClassName("role-info");
        roleInfo.addClassName(myMove ? "role-info-my-lose" : "role-info-other");

        Span hint = new Span("Work together - try a different order!");
        hint.addClassName("lose-hint");

        Button retryBtn = new Button("Restart Level", e -> {
            animatingUntil = 0;
            gameService.restartLevel();
            closeDialog();
        });
        styleButton(retryBtn, true);
        retryBtn.addClassName("dialog-btn-top");

        content.add(loseTitle, roleInfo, hint, retryBtn);
        dialog.add(content);
        dialog.open();
        currentDialog = dialog;
    }

    private void closeDialog() {
        if (currentDialog != null) {
            currentDialog.close();
            currentDialog = null;
        }
    }

    // ====== Helpers ======

    private void appendStop(Element gradient, String offset, String color, String opacity) {
        Element stop = new Element("stop");
        stop.setAttribute("offset", offset);
        stop.setAttribute("stop-color", color);
        stop.setAttribute("stop-opacity", opacity);
        gradient.appendChild(stop);
    }

    private String heartsDisplay(int hearts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(i < hearts ? "\u2764" : "\u2661");
            if (i < 2) sb.append(" ");
        }
        return sb.toString();
    }

    private VerticalLayout buildStat(String label, Span valueSpan) {
        VerticalLayout col = new VerticalLayout();
        col.setPadding(false);
        col.setSpacing(false);
        col.setAlignItems(Alignment.CENTER);
        col.addClassName("stat-col");
        Span labelSpan = new Span(label);
        labelSpan.addClassName("stat-label");
        col.add(labelSpan, valueSpan);
        return col;
    }

    private void styleButton(Button btn, boolean primary) {
        btn.addClassName(primary ? "btn-primary" : "btn-secondary");
    }

    // ====== Lifecycle ======

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);

        // Inject JS pipe-flow animation functions (once per page)
        getElement().executeJs("""
                if (!window._buildPipePath) {

                    // Build an SVG polyline path through segment centers + extension cells,
                    // returning the path string and cumulative segment lengths.
                    window._buildPipePath = function(centersX, centersY, segCount, headDirX, headDirY,
                                                     cellStep, margin, cellSize, extraCells) {
                        var dirX, dirY;
                        if (segCount > 1) {
                            dirX = centersX[1] - centersX[0];
                            dirY = centersY[1] - centersY[0];
                        } else {
                            dirX = headDirX * cellStep;
                            dirY = headDirY * cellStep;
                        }
                        var dirLen = Math.sqrt(dirX * dirX + dirY * dirY);
                        if (dirLen > 0.01) { dirX /= dirLen; dirY /= dirLen; }

                        var tailExtend = cellSize / 2 - margin;
                        var points = [[centersX[0] - dirX * tailExtend, centersY[0] - dirY * tailExtend]];
                        for (var i = 0; i < segCount; i++) {
                            points.push([centersX[i], centersY[i]]);
                        }
                        for (var k = 1; k <= extraCells; k++) {
                            points.push([
                                centersX[segCount - 1] + k * headDirX * cellStep,
                                centersY[segCount - 1] + k * headDirY * cellStep
                            ]);
                        }

                        var fmt = function(v) { return v.toFixed(1); };
                        var pathData = 'M' + fmt(points[0][0]) + ',' + fmt(points[0][1]);
                        var totalLength = 0;
                        var cumLengths = [0];
                        for (var i = 1; i < points.length; i++) {
                            pathData += ' L' + fmt(points[i][0]) + ',' + fmt(points[i][1]);
                            var dx = points[i][0] - points[i - 1][0];
                            var dy = points[i][1] - points[i - 1][1];
                            totalLength += Math.sqrt(dx * dx + dy * dy);
                            cumLengths.push(totalLength);
                        }
                        return {
                            pathData: pathData,
                            totalLength: totalLength,
                            cumLengths: cumLengths,
                            snakeLength: cumLengths[segCount] + tailExtend,
                            tailExtend: tailExtend
                        };
                    };

                    // Pipe-flow exit animation:
                    // Replaces arrow shape with a thick stroked line, slides it forward
                    // using stroke-dashoffset. Like water flowing through a pipe.
                    window._animateExit = function(el, centersX, centersY, headDirX, headDirY,
                                                   cellStep, bodyHalf, headHalf, margin, cellSize,
                                                   totalSteps, duration) {
                        var segCount = centersX.length;
                        var paths = el.querySelectorAll('path');
                        var color = el.querySelector('.arrow-body').getAttribute('fill');
                        for (var i = 0; i < paths.length; i++) paths[i].style.display = 'none';

                        var pipe = window._buildPipePath(centersX, centersY, segCount,
                            headDirX, headDirY, cellStep, margin, cellSize, totalSteps + 2);
                        var svgNs = 'http://www.w3.org/2000/svg';
                        var pipeEl = document.createElementNS(svgNs, 'path');
                        pipeEl.setAttribute('d', pipe.pathData);
                        pipeEl.setAttribute('fill', 'none');
                        pipeEl.setAttribute('stroke', color);
                        pipeEl.setAttribute('stroke-width', String(bodyHalf * 2.2));
                        pipeEl.setAttribute('stroke-linecap', 'round');
                        pipeEl.setAttribute('stroke-linejoin', 'round');
                        pipeEl.setAttribute('stroke-dasharray',
                            pipe.snakeLength.toFixed(1) + ' ' + String(pipe.totalLength * 2));
                        el.appendChild(pipeEl);

                        var startTime = performance.now();
                        function frame(now) {
                            var progress = Math.min(1, (now - startTime) / duration);
                            pipeEl.setAttribute('stroke-dashoffset', String(-progress * pipe.totalLength));
                            if (progress < 1) requestAnimationFrame(frame);
                            else el.style.display = 'none';
                        }
                        el.getBoundingClientRect();
                        requestAnimationFrame(frame);
                    };

                    // Bump animation:
                    // Translates the entire arrow group forward toward the collision,
                    // then bounces it back. Preserves the arrow shape and arrowhead.
                    window._animateBump = function(el, headDirX, headDirY,
                                                   cellStep, bumpDist, duration) {
                        var body = el.querySelector('.arrow-body');
                        var originalColor = body ? body.getAttribute('fill') : null;

                        var maxDist = bumpDist * cellStep;
                        var peakFraction = 0.4;
                        var startTime = performance.now();
                        function frame(now) {
                            var progress = Math.min(1, (now - startTime) / duration);
                            var offset;
                            if (progress < peakFraction) {
                                // Ease-out forward phase
                                var forwardT = progress / peakFraction;
                                offset = forwardT * (2 - forwardT) * maxDist;
                                // Flash red as arrow approaches collision
                                if (body) body.setAttribute('fill', '#c62828');
                            } else {
                                // Smooth-step backward phase
                                var backT = (progress - peakFraction) / (1 - peakFraction);
                                offset = (1 - backT * backT * (3 - 2 * backT)) * maxDist;
                                // Restore color on return
                                if (body && originalColor) body.setAttribute('fill', originalColor);
                            }
                            var tx = headDirX * offset;
                            var ty = headDirY * offset;
                            el.setAttribute('transform', 'translate(' + tx.toFixed(1) + ',' + ty.toFixed(1) + ')');
                            if (progress < 1) requestAnimationFrame(frame);
                            else el.removeAttribute('transform');
                        }
                        el.getBoundingClientRect();
                        requestAnimationFrame(frame);
                    };
                }
                """);
    }
}
