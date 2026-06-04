package io.github.xvym.pmocr;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

final class RegionSelector {
    private RegionSelector() {
    }

    static void select(JFrame owner, Consumer<Rectangle> callback) {
        Rectangle virtualBounds = virtualScreenBounds();
        final JWindow window = new JWindow(owner);
        final SelectionPanel panel = new SelectionPanel(virtualBounds, window, callback);
        window.setBounds(virtualBounds);
        window.setAlwaysOnTop(true);
        window.setBackground(new Color(0, 0, 0, 0));
        window.setContentPane(panel);
        window.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "cancel");
        panel.getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                window.dispose();
                callback.accept(null);
            }
        });
        window.setVisible(true);
        window.requestFocus();
    }

    private static Rectangle virtualScreenBounds() {
        Rectangle result = new Rectangle();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            result = result.union(device.getDefaultConfiguration().getBounds());
        }
        return result;
    }

    private static final class SelectionPanel extends JPanel {
        private final Rectangle virtualBounds;
        private final JWindow window;
        private final Consumer<Rectangle> callback;
        private Point start;
        private Point end;

        SelectionPanel(Rectangle virtualBounds, JWindow window, Consumer<Rectangle> callback) {
            this.virtualBounds = virtualBounds;
            this.window = window;
            this.callback = callback;
            setOpaque(false);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    start = e.getPoint();
                    end = start;
                    repaint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    end = e.getPoint();
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    end = e.getPoint();
                    Rectangle selected = selection();
                    window.dispose();
                    if (selected.width < 20 || selected.height < 20) {
                        callback.accept(null);
                        return;
                    }
                    selected.translate(virtualBounds.x, virtualBounds.y);
                    callback.accept(selected);
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        public Dimension getPreferredSize() {
            return virtualBounds.getSize();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Color.WHITE);
            g.setFont(getFont().deriveFont(Font.BOLD, 24f));
            g.drawString("拖动圈选模拟器游戏画面，Esc 取消", 24, 42);
            if (start != null && end != null) {
                Rectangle selected = selection();
                g.setComposite(java.awt.AlphaComposite.Clear);
                g.fill(selected);
                g.setComposite(java.awt.AlphaComposite.SrcOver);
                g.setColor(new Color(255, 80, 80));
                g.setStroke(new BasicStroke(2f));
                g.draw(selected);
                g.setColor(Color.WHITE);
                g.drawString(selected.width + " x " + selected.height,
                        selected.x + 6, Math.max(20, selected.y - 6));
            }
            g.dispose();
        }

        private Rectangle selection() {
            int x = Math.min(start.x, end.x);
            int y = Math.min(start.y, end.y);
            return new Rectangle(x, y, Math.abs(start.x - end.x), Math.abs(start.y - end.y));
        }
    }
}
