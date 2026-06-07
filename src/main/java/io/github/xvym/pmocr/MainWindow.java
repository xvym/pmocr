package io.github.xvym.pmocr;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

final class MainWindow extends JFrame {
    private static final Font BUTTON_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 18);
    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 16);
    private static final Font OUTPUT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 28);

    private final JTextArea output = new JTextArea(6, 34);
    private final JLabel status = new JLabel("请先圈选模拟器游戏画面");
    private final JLabel areaLabel = new JLabel("未选择区域");
    private final JButton startButton = new JButton("开始实时识别");
    private final JButton stopButton = new JButton("停止");
    private final RealtimeRecognizer recognizer;
    private Rectangle captureArea;

    MainWindow() {
        super("宝可梦 金/银 日文像素文字 OCR @自信过剩");
        recognizer = new RealtimeRecognizer(new PokemonOcr(), new RealtimeRecognizer.Listener() {
            @Override
            public void onStatus(final String value) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(value);
                    }
                });
            }

            @Override
            public void onText(final PokemonOcr.Recognition recognition) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        output.setText(recognition.getText());
                    }
                });
            }
        });
        initialize();
    }

    private void initialize() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel selection = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton selectButton = new JButton("圈选游戏区域");
        selectButton.setFont(BUTTON_FONT);
        areaLabel.setFont(LABEL_FONT);
        selectButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                recognizer.stop();
                setVisible(false);
                Timer timer = new Timer(150, new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        RegionSelector.select(MainWindow.this, selected -> {
                            setVisible(true);
                            if (selected != null) {
                                captureArea = selected;
                                areaLabel.setText(String.format("区域: x=%d, y=%d, %d x %d",
                                        selected.x, selected.y, selected.width, selected.height));
                                status.setText("区域已选择，可开始识别");
                                updateButtons();
                            }
                        });
                    }
                });
                timer.setRepeats(false);
                timer.start();
            }
        });
        selection.add(selectButton);
        selection.add(areaLabel);

        output.setEditable(false);
        output.setLineWrap(true);
        output.setWrapStyleWord(false);
        output.setFont(OUTPUT_FONT);
        output.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        startButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startRecognition();
            }
        });
        stopButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                recognizer.stop();
                status.setText("已停止");
                updateButtons();
            }
        });
        JButton copyButton = new JButton("复制文字");
        copyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(output.getText()), null);
                status.setText("文字已复制");
            }
        });
        startButton.setFont(BUTTON_FONT);
        stopButton.setFont(BUTTON_FONT);
        copyButton.setFont(BUTTON_FONT);
        controls.add(startButton);
        controls.add(stopButton);
        controls.add(copyButton);

        status.setForeground(new Color(45, 75, 120));
        status.setFont(LABEL_FONT);
        status.setBorder(BorderFactory.createEmptyBorder(6, 4, 4, 4));
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(controls, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.SOUTH);

        add(selection, BorderLayout.NORTH);
        add(new JScrollPane(output), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                recognizer.stop();
            }
        });
        setAlwaysOnTop(true);
        pack();
        setLocationByPlatform(true);
        updateButtons();
    }

    private void startRecognition() {
        if (captureArea == null) {
            status.setText("请先圈选游戏区域");
            return;
        }
        try {
            recognizer.start(captureArea);
            status.setText("实时识别已启动");
        } catch (Exception e) {
            status.setText("无法截取屏幕: " + e.getMessage());
        }
        updateButtons();
    }

    private void updateButtons() {
        startButton.setEnabled(captureArea != null && !recognizer.isRunning());
        stopButton.setEnabled(recognizer.isRunning());
    }
}
