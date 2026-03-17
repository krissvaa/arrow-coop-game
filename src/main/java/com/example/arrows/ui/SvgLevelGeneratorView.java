package com.example.arrows.ui;

import com.example.arrows.data.GeneratedLevelStore;
import com.example.arrows.model.Arrow;
import com.example.arrows.model.Level;
import com.example.arrows.service.LevelGenerator;
import com.example.arrows.service.LevelSolver;
import com.example.arrows.signals.GameService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Route("generate")
public class SvgLevelGeneratorView extends VerticalLayout {

    private final LevelGenerator generator;
    private final LevelSolver solver;
    private final GeneratedLevelStore store;
    private final GameService signals;

    private String currentSvg;
    private String selectedPresetName;
    private int gridSize = 20;
    private int density = 25;
    private Level generatedLevel;

    private Div previewGrid;
    private Span statusBadge;
    private Span moveEstimate;
    private Button saveBtn;
    private Button regenerateBtn;
    private final List<Button> presetButtons = new ArrayList<>();

    // Difficulty options: label -> density value
    private record DifficultyOption(String label, int density) {
        @Override public String toString() { return label; }
    }

    private static final DifficultyOption[] DIFFICULTIES = {
        new DifficultyOption("Easy (few long arrows)", 0),
        new DifficultyOption("Medium", 35),
        new DifficultyOption("Hard (many short arrows)", 70),
        new DifficultyOption("Expert", 100),
    };

    public SvgLevelGeneratorView(LevelGenerator generator,
                                  LevelSolver solver,
                                  GeneratedLevelStore store,
                                  GameService signals) {
        this.generator = generator;
        this.solver = solver;
        this.store = store;
        this.signals = signals;

        setSizeFull();
        setPadding(true);
        setAlignItems(Alignment.CENTER);
        addClassName("generator-view");

        // Header
        H2 title = new H2("Level Generator");
        title.addClassName("generator-title");
        add(title);

        RouterLink backLink = new RouterLink("< Back to Game", GameView.class);
        backLink.addClassName("back-link");
        add(backLink);

        // Two-column layout
        HorizontalLayout columns = new HorizontalLayout();
        columns.setWidthFull();
        columns.addClassName("generator-columns");

        VerticalLayout leftPanel = buildLeftPanel();
        leftPanel.setWidth("50%");

        VerticalLayout rightPanel = buildRightPanel();
        rightPanel.setWidth("50%");

        columns.add(leftPanel, rightPanel);
        add(columns);
    }

    private VerticalLayout buildLeftPanel() {
        VerticalLayout panel = new VerticalLayout();
        panel.setPadding(false);
        panel.setSpacing(true);

        // --- Shape selection section ---
        Span shapeLabel = new Span("Shape");
        shapeLabel.addClassName("section-label");
        panel.add(shapeLabel);

        // Preset grid
        Span presetLabel = new Span("Pick a preset:");
        presetLabel.addClassName("sub-label");
        panel.add(presetLabel);

        FlexLayout presetsGrid = new FlexLayout();
        presetsGrid.addClassName("presets-grid");

        addPresetButton(presetsGrid, "Heart", "heart.svg");
        addPresetButton(presetsGrid, "Star", "star.svg");
        addPresetButton(presetsGrid, "Smiley", "smiley.svg");
        addPresetButton(presetsGrid, "Cat", "cat.svg");
        addPresetButton(presetsGrid, "Skull", "skull.svg");
        addPresetButton(presetsGrid, "Crown", "crown.svg");
        addPresetButton(presetsGrid, "Rocket", "rocket.svg");
        addPresetButton(presetsGrid, "Paw", "paw.svg");
        addPresetButton(presetsGrid, "Lightning", "lightning.svg");
        addPresetButton(presetsGrid, "Moon", "moon.svg");
        addPresetButton(presetsGrid, "Cross", "cross.svg");
        addPresetButton(presetsGrid, "Tree", "tree.svg");
        panel.add(presetsGrid);

        // Upload
        Span orLabel = new Span("Or upload your own SVG:");
        orLabel.addClassName("sub-label-upload");
        panel.add(orLabel);

        Upload upload = new Upload(UploadHandler.inMemory((metadata, data) -> {
            try {
                currentSvg = new String(data, StandardCharsets.UTF_8);
                selectedPresetName = metadata.fileName();
                getUI().ifPresent(ui -> ui.access(() -> {
                    updatePresetSelection();
                    Notification.show("SVG loaded: " + metadata.fileName(),
                            3000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                }));
            } catch (Exception e) {
                getUI().ifPresent(ui -> ui.access(() ->
                    Notification.show("Error reading SVG: " + e.getMessage(),
                            3000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR)
                ));
            }
        }));
        upload.setAcceptedFileTypes(".svg", "image/svg+xml");
        upload.setMaxFiles(1);
        upload.setMaxFileSize(1024 * 1024);
        panel.add(upload);

        // --- Settings section ---
        Span settingsLabel = new Span("Settings");
        settingsLabel.addClassName("section-label-settings");
        panel.add(settingsLabel);

        // Grid size
        IntegerField gridSizeField = new IntegerField("Grid size");
        gridSizeField.setValue(gridSize);
        gridSizeField.setMin(4);
        gridSizeField.setMax(40);
        gridSizeField.setStep(1);
        gridSizeField.setStepButtonsVisible(true);
        gridSizeField.setHelperText("4 - 40");
        gridSizeField.setWidth("160px");
        gridSizeField.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                gridSize = e.getValue();
            }
        });
        panel.add(gridSizeField);

        // Difficulty
        Select<DifficultyOption> difficultySelect = new Select<>();
        difficultySelect.setLabel("Difficulty");
        difficultySelect.setItems(DIFFICULTIES);
        difficultySelect.setItemLabelGenerator(DifficultyOption::label);
        difficultySelect.setValue(DIFFICULTIES[1]); // Medium
        difficultySelect.setWidth("260px");
        difficultySelect.setHelperText("Controls arrow length: easy = fewer long arrows, hard = many short arrows");
        difficultySelect.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                density = e.getValue().density();
            }
        });
        density = DIFFICULTIES[1].density(); // sync default
        panel.add(difficultySelect);

        // Generate button
        Button generateBtn = new Button("Generate Preview", e -> generatePreview());
        generateBtn.addThemeVariants(ButtonVariant.PRIMARY);
        generateBtn.addClassName("generate-btn");
        panel.add(generateBtn);

        return panel;
    }

    private VerticalLayout buildRightPanel() {
        VerticalLayout panel = new VerticalLayout();
        panel.setPadding(false);
        panel.setAlignItems(Alignment.CENTER);

        Span previewLabel = new Span("Preview");
        previewLabel.addClassName("section-label");
        panel.add(previewLabel);

        previewGrid = new Div();
        previewGrid.addClassName("preview-grid");

        Span placeholder = new Span("Select a shape, then click Generate");
        placeholder.addClassName("placeholder-text");
        previewGrid.add(placeholder);
        panel.add(previewGrid);

        // Status
        statusBadge = new Span("");
        statusBadge.addClassName("status-badge");
        panel.add(statusBadge);

        moveEstimate = new Span("");
        moveEstimate.addClassName("move-estimate");
        panel.add(moveEstimate);

        // Actions
        HorizontalLayout actions = new HorizontalLayout();
        actions.addClassName("actions-top");

        saveBtn = new Button("Save & Play", e -> saveAndPlay());
        saveBtn.addThemeVariants(ButtonVariant.PRIMARY);
        saveBtn.setEnabled(false);
        saveBtn.addClassName("mono-btn");

        regenerateBtn = new Button("Regenerate", e -> regenerate());
        regenerateBtn.addThemeVariants(ButtonVariant.TERTIARY);
        regenerateBtn.setEnabled(false);
        regenerateBtn.addClassName("mono-btn");

        actions.add(saveBtn, regenerateBtn);
        panel.add(actions);

        return panel;
    }

    private void addPresetButton(FlexLayout container, String label, String filename) {
        Button btn = new Button(label, e -> {
            try {
                InputStream is = getClass().getResourceAsStream("/presets/" + filename);
                if (is != null) {
                    currentSvg = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    selectedPresetName = label;
                    updatePresetSelection();
                } else {
                    Notification.show("Preset not found: " + filename,
                            3000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR);
                }
            } catch (Exception ex) {
                Notification.show("Error loading preset: " + ex.getMessage(),
                        3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        btn.addThemeVariants(ButtonVariant.SMALL);
        btn.addClassName("preset-btn");
        btn.getElement().setAttribute("data-preset", label);
        presetButtons.add(btn);
        container.add(btn);
    }

    private void updatePresetSelection() {
        for (Button btn : presetButtons) {
            String preset = btn.getElement().getAttribute("data-preset");
            boolean selected = preset != null && preset.equals(selectedPresetName);
            if (selected) {
                btn.addThemeVariants(ButtonVariant.PRIMARY);
                btn.addClassName("preset-btn-selected");
            } else {
                btn.removeThemeVariants(ButtonVariant.PRIMARY);
                btn.removeClassName("preset-btn-selected");
            }
        }
    }

    private void generatePreview() {
        if (currentSvg == null || currentSvg.isBlank()) {
            Notification.show("Please select a shape first.",
                    3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_CONTRAST);
            return;
        }

        try {
            long seed = ThreadLocalRandom.current().nextLong();
            generatedLevel = generator.generate(currentSvg, gridSize, density, seed);
            if (generatedLevel != null) {
                renderPreview(generatedLevel);
                statusBadge.setText(generatedLevel.getArrows().size() + " arrows — Solvable!");
                statusBadge.removeClassName("status-unsolvable");
                statusBadge.addClassName("status-solvable");

                Optional<List<String>> solution = solver.solve(generatedLevel);
                solution.ifPresent(sol ->
                    moveEstimate.setText("Optimal: " + sol.size() + " moves"));

                saveBtn.setEnabled(true);
                regenerateBtn.setEnabled(true);
            } else {
                previewGrid.removeAll();
                statusBadge.setText("No solution found — try different settings");
                statusBadge.removeClassName("status-solvable");
                statusBadge.addClassName("status-unsolvable");
                moveEstimate.setText("");
                saveBtn.setEnabled(false);
            }
        } catch (IllegalArgumentException e) {
            Notification.show(e.getMessage(), 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            statusBadge.setText(e.getMessage());
            statusBadge.removeClassName("status-solvable");
            statusBadge.addClassName("status-unsolvable");
        } catch (Exception e) {
            Notification.show("Error: " + e.getMessage(), 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            statusBadge.setText("Error: " + e.getMessage());
            statusBadge.removeClassName("status-solvable");
            statusBadge.addClassName("status-unsolvable");
        }
    }

    private void renderPreview(Level level) {
        previewGrid.removeAll();
        int gs = level.getGridSize();

        int boardTarget = 440;
        int cellPad = 1;
        int boardPad = 4;
        int cs = Math.max(4, (boardTarget - boardPad * 2 - (gs - 1) * cellPad) / gs);
        int totalSize = boardPad * 2 + gs * cs + (gs - 1) * cellPad;

        Element svg = new Element("svg");
        svg.setAttribute("viewBox", "0 0 " + totalSize + " " + totalSize);
        svg.setAttribute("width", String.valueOf(Math.min(totalSize, 440)));
        svg.setAttribute("height", String.valueOf(Math.min(totalSize, 440)));
        svg.setAttribute("class", "preview-svg");

        Element bg = new Element("rect");
        bg.setAttribute("width", String.valueOf(totalSize));
        bg.setAttribute("height", String.valueOf(totalSize));
        bg.setAttribute("rx", "8");
        bg.setAttribute("fill", "#0f0f23");
        svg.appendChild(bg);

        boolean[][] mask = level.getPlayableMask();
        for (int r = 0; r < gs; r++) {
            for (int c = 0; c < gs; c++) {
                if (mask != null && !mask[r][c]) continue;
                Element cell = new Element("rect");
                cell.setAttribute("x", String.valueOf(boardPad + c * (cs + cellPad)));
                cell.setAttribute("y", String.valueOf(boardPad + r * (cs + cellPad)));
                cell.setAttribute("width", String.valueOf(cs));
                cell.setAttribute("height", String.valueOf(cs));
                cell.setAttribute("rx", String.valueOf(Math.max(1, cs / 8)));
                cell.setAttribute("fill", "#1a1a2e");
                svg.appendChild(cell);
            }
        }

        for (Arrow arrow : level.getArrows()) {
            List<int[]> segs = arrow.getSegments();
            String color = arrow.getColor();
            int inner = Math.max(2, cs - 2);
            int offset = (cs - inner) / 2;

            for (int si = 0; si < segs.size(); si++) {
                int[] seg = segs.get(si);
                boolean isHead = (si == segs.size() - 1);

                int x = boardPad + seg[1] * (cs + cellPad) + offset;
                int y = boardPad + seg[0] * (cs + cellPad) + offset;

                Element rect = new Element("rect");
                rect.setAttribute("x", String.valueOf(x));
                rect.setAttribute("y", String.valueOf(y));
                rect.setAttribute("width", String.valueOf(inner));
                rect.setAttribute("height", String.valueOf(inner));
                rect.setAttribute("rx", String.valueOf(Math.max(1, isHead ? inner / 4 : inner / 8)));
                rect.setAttribute("fill", color);
                if (!isHead) {
                    rect.setAttribute("opacity", "0.75");
                }
                svg.appendChild(rect);

                if (isHead && cs >= 8) {
                    int cx = x + inner / 2;
                    int cy = y + inner / 2;
                    int sz = Math.max(2, inner / 3);
                    String points = trianglePoints(cx, cy, sz, arrow.getDirection().rotationDeg());
                    Element tri = new Element("polygon");
                    tri.setAttribute("points", points);
                    tri.setAttribute("fill", "white");
                    svg.appendChild(tri);
                }
            }
        }

        Div wrapper = new Div();
        wrapper.getElement().appendChild(svg);
        previewGrid.add(wrapper);
    }

    private String trianglePoints(int cx, int cy, int size, int rotDeg) {
        double rad = Math.toRadians(rotDeg);
        double tipX = cx + Math.cos(rad) * size;
        double tipY = cy + Math.sin(rad) * size;
        double lx = cx + Math.cos(rad + 2.4) * size * 0.7;
        double ly = cy + Math.sin(rad + 2.4) * size * 0.7;
        double rx = cx + Math.cos(rad - 2.4) * size * 0.7;
        double ry = cy + Math.sin(rad - 2.4) * size * 0.7;
        return f(tipX) + "," + f(tipY) + " " + f(lx) + "," + f(ly) + " " + f(rx) + "," + f(ry);
    }

    private static String f(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private void saveAndPlay() {
        if (generatedLevel == null) return;
        store.save(generatedLevel);
        signals.loadCustomLevel(generatedLevel);
        getUI().ifPresent(ui -> ui.navigate(""));
    }

    private void regenerate() {
        generatePreview();
    }
}
