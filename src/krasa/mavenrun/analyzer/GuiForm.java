package krasa.mavenrun.analyzer;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.*;

import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.model.MavenArtifact;
import org.jetbrains.idea.maven.model.MavenArtifactNode;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.jetbrains.idea.maven.server.MavenServerManager;

import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.*;
import com.intellij.ui.components.JBList;
import com.intellij.ui.treeStructure.Tree;

/**
 * @author Vojtech Krasa
 */
public class GuiForm {
	private static final Logger LOG = Logger.getInstance("#krasa.mavenrun.analyzer.GuiForm");

	public static final String WARNING = "Your settings indicates, that conflicts will not be visible, see IDEA-133331\n"
			+ "If your project is Maven2 compatible, you could try one of the following:\n"
			+ "-press Apply Fix button to alter Maven VM options for importer\n"
			+ "-turn off File | Settings | Build, Execution, Deployment | Build Tools | Maven | Importing | Use Maven3 to import project setting";
	protected static final Comparator<MavenArtifactNode> BY_ARTICATF_ID = new Comparator<MavenArtifactNode>() {
		@Override
		public int compare(MavenArtifactNode o1, MavenArtifactNode o2) {
			return o1.getArtifact().getArtifactId().compareTo(o2.getArtifact().getArtifactId());
		}
	};

	private final Project project;
	private final VirtualFile file;
	private MavenProject mavenProject;
	private JBList leftPanelList;
	private JTree rightTree;
	private JPanel rootPanel;

	private JRadioButton allDependenciesAsListRadioButton;
	private JRadioButton conflictsRadioButton;
	private JRadioButton allDependenciesAsTreeRadioButton;

	private JLabel noConflictsLabel;
	private JTextPane noConflictsWarningLabel;
	private JButton refreshButton;
	private JSplitPane splitPane;
	private SearchTextField searchField;
	private JScrollPane noConflictsWarningLabelScrollPane;
	private JButton applyMavenVmOptionsFixButton;
	private JPanel leftPanelWrapper;
	private JTree leftPanelTree;
	protected DefaultListModel listDataModel;
	protected Map<String, List<MavenArtifactNode>> allArtifactsMap;
	protected DefaultTreeModel rightTreeModel;
	protected DefaultTreeModel leftTreeModel;
	protected DefaultMutableTreeNode rightTreeRoot;
	protected DefaultMutableTreeNode leftTreeRoot;
	protected ListSpeedSearch myListSpeedSearch;
	protected List<MavenArtifactNode> dependencyTree;

	public GuiForm(final Project project, VirtualFile file, final MavenProject mavenProject) {
		this.project = project;
		this.file = file;
		this.mavenProject = mavenProject;
		final ActionListener l = new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				updateLeftPanelModel();
			}
		};
		conflictsRadioButton.addActionListener(l);
		allDependenciesAsListRadioButton.addActionListener(l);
		allDependenciesAsTreeRadioButton.addActionListener(l);

		refreshButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				initializeModel();
				rootPanel.requestFocus();
			}
		});
		rightTree.addMouseListener(new TreePopupHandler(project, mavenProject, rightTree));
		// todo fix excluding
		// leftPanelTree.addMouseListener(new TreePopupHandler(project, mavenProject, leftPanelTree));
		myListSpeedSearch = new ListSpeedSearch(leftPanelList);
		searchField.addDocumentListener(new DocumentAdapter() {
			@Override
			protected void textChanged(DocumentEvent documentEvent) {
				filter();
			}
		});
		searchField.getTextEditor().addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				if (searchField.getText() != null) {
					searchField.addCurrentTextToHistory();
				}
			}
		});
		noConflictsWarningLabel.setBackground(null);
		applyMavenVmOptionsFixButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				String mavenEmbedderVMOptions = MavenServerManager.getInstance().getMavenEmbedderVMOptions();
				int baselineVersion = ApplicationInfoEx.getInstanceEx().getBuild().getBaselineVersion();
				if (baselineVersion >= 140) {
					mavenEmbedderVMOptions += " -Didea.maven3.use.compat.resolver";
				} else {
					mavenEmbedderVMOptions += " -Dmaven3.use.compat.resolver";
				}
				MavenServerManager.getInstance().setMavenEmbedderVMOptions(mavenEmbedderVMOptions);
				final MavenProjectsManager projectsManager = MavenProjectsManager.getInstance(project);
				projectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles();
				refreshButton.getActionListeners()[0].actionPerformed(e);
			}
		});
		noConflictsWarningLabel.setText(WARNING);
		leftPanelTree.addTreeSelectionListener(new LeftTreeSelectionListener());
	}

	public static String sortByVersion(List<MavenArtifactNode> value) {
		Collections.sort(value, new Comparator<MavenArtifactNode>() {
			@Override
			public int compare(MavenArtifactNode o1, MavenArtifactNode o2) {
				DefaultArtifactVersion version = new DefaultArtifactVersion(o1.getArtifact().getVersion());
				DefaultArtifactVersion version1 = new DefaultArtifactVersion(o2.getArtifact().getVersion());
				return version1.compareTo(version);
			}
		});
		return value.get(0).getArtifact().getVersion();
	}

	private void filter() {
		updateLeftPanelModel();
	}

	private void createUIComponents() {
		listDataModel = new DefaultListModel();
		leftPanelList = createJBList(listDataModel);
		// no generics in IJ12
		leftPanelList.setCellRenderer(new ColoredListCellRenderer() {
			@Override
			protected void customizeCellRenderer(JList jList, Object o, int i, boolean b, boolean b2) {
				MyListNode value = (MyListNode) o;
				String maxVersion = value.getMaxVersion();
				final String[] split = value.key.split(":");
				append(split[0] + " : ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
				append(split[1], SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
				append(" : " + maxVersion, SimpleTextAttributes.REGULAR_ATTRIBUTES);

			}
		});
		rightTree = new Tree();
		rightTreeRoot = new DefaultMutableTreeNode();
		rightTreeModel = new DefaultTreeModel(rightTreeRoot);
		rightTree.setModel(rightTreeModel);
		rightTree.setRootVisible(false);
		rightTree.setShowsRootHandles(true);
		rightTree.expandPath(new TreePath(rightTreeRoot.getPath()));
		rightTree.setCellRenderer(new TreeRenderer());
		rightTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);

		leftPanelTree = new MyHighlightingTree();
		leftTreeRoot = new DefaultMutableTreeNode();
		leftTreeModel = new DefaultTreeModel(leftTreeRoot);
		leftPanelTree.setModel(leftTreeModel);
		leftPanelTree.setRootVisible(false);
		leftPanelTree.setShowsRootHandles(true);
		leftPanelTree.expandPath(new TreePath(leftTreeRoot.getPath()));
		leftPanelTree.setCellRenderer(new TreeRenderer());
		leftPanelTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
	}

	private class LeftTreeSelectionListener implements TreeSelectionListener {
		@Override
		public void valueChanged(TreeSelectionEvent e) {
			TreePath selectionPath = e.getPath();
			if (selectionPath != null) {
				DefaultMutableTreeNode lastPathComponent = (DefaultMutableTreeNode) selectionPath.getLastPathComponent();
				MyTreeUserObject userObject = (MyTreeUserObject) lastPathComponent.getUserObject();

				final String key = getArtifactKey(userObject.getArtifact());
				List<MavenArtifactNode> mavenArtifactNodes = allArtifactsMap.get(key);
				fillRightTree(mavenArtifactNodes, sortByVersion(mavenArtifactNodes));
			}
		}
	}

	private class MyListSelectionListener implements ListSelectionListener {
		@Override
		public void valueChanged(ListSelectionEvent e) {
			if (listDataModel.isEmpty() || leftPanelList.getSelectedValue() == null) {
				return;
			}

			final MyListNode myListNode = (MyListNode) leftPanelList.getSelectedValue();
			List<MavenArtifactNode> artifacts = myListNode.value;
			fillRightTree(artifacts, myListNode.getMaxVersion());
		}

	}

	private void fillRightTree(List<MavenArtifactNode> mavenArtifactNodes, String maxVersion) {
		rightTreeRoot.removeAllChildren();
		for (MavenArtifactNode mavenArtifactNode : mavenArtifactNodes) {
			MyTreeUserObject userObject = MyTreeUserObject.create(mavenArtifactNode, maxVersion);
			userObject.showOnlyVersion = true;
			final DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(userObject);
			fillRightTree(mavenArtifactNode, newNode);
			rightTreeRoot.add(newNode);
		}
		rightTreeModel.nodeStructureChanged(rightTreeRoot);
		expandAll(rightTree, new TreePath(rightTreeRoot.getPath()));
	}

	private void fillRightTree(MavenArtifactNode mavenArtifactNode, DefaultMutableTreeNode node) {
		final MavenArtifactNode parent = mavenArtifactNode.getParent();
		if (parent == null) {
			return;
		}
		final DefaultMutableTreeNode parentDependencyNode = new DefaultMutableTreeNode(new MyTreeUserObject(parent));
		node.add(parentDependencyNode);
		parentDependencyNode.setParent(node);
		fillRightTree(parent, parentDependencyNode);
	}

	private void expandAll(JTree tree, TreePath parent) {
		TreeNode node = (TreeNode) parent.getLastPathComponent();
		if (node.getChildCount() >= 0) {
			for (Enumeration e = node.children(); e.hasMoreElements();) {
				TreeNode n = (TreeNode) e.nextElement();
				TreePath path = parent.pathByAddingChild(n);
				expandAll(tree, path);
			}
		}
		tree.expandPath(parent);
	}

	private JBList createJBList(DefaultListModel model) {
		JBList jbList = new JBList(model);
		jbList.setCellRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected,
					boolean cellHasFocus) {
				final Component comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				MyListNode listNode = (MyListNode) value;
				setText(listNode.toString());
				return comp;
			}
		});
		jbList.addListSelectionListener(new MyListSelectionListener());
		return jbList;
	}

	private void initializeModel() {
		final Object selectedValue = leftPanelList.getSelectedValue();

		dependencyTree = mavenProject.getDependencyTree();
		allArtifactsMap = createAllArtifactsMap(dependencyTree);
		updateLeftPanelModel();

		rightTreeRoot.removeAllChildren();
		rightTreeModel.reload();
		leftPanelWrapper.revalidate();

		if (selectedValue != null) {
			leftPanelList.setSelectedValue(selectedValue, true);
		}
	}

	private void updateLeftPanelModel() {
		final String searchFieldText = searchField.getText();
		listDataModel.clear();
		leftTreeRoot.removeAllChildren();
		boolean conflictsWarning = false;
		boolean showNoConflictsLabel = false;
		if (conflictsRadioButton.isSelected()) {
			for (Map.Entry<String, List<MavenArtifactNode>> s : allArtifactsMap.entrySet()) {
				final List<MavenArtifactNode> nodes = s.getValue();
				if (nodes.size() > 1 && hasConflicts(nodes)) {
					if (searchFieldText == null || s.getKey().contains(searchFieldText)) {
						listDataModel.addElement(new MyListNode(s));
					}
				}
			}
			showNoConflictsLabel = listDataModel.isEmpty();
			int baselineVersion = ApplicationInfoEx.getInstanceEx().getBuild().getBaselineVersion();
			if (showNoConflictsLabel && baselineVersion >= 139) {
				MavenServerManager server = MavenServerManager.getInstance();
				boolean useMaven2 = server.isUseMaven2();
				boolean contains139 = server.getMavenEmbedderVMOptions().contains("-Dmaven3.use.compat.resolver");
				boolean contains140 = server.getMavenEmbedderVMOptions().contains("-Didea.maven3.use.compat.resolver");
				boolean containsProperty = (baselineVersion == 139 && contains139)
						|| (baselineVersion >= 140 && contains140);
				conflictsWarning = !containsProperty && !useMaven2;
			}

			leftPanelTree.getParent().getParent().setVisible(false);
			leftPanelList.getParent().getParent().setVisible(true);
		} else if (allDependenciesAsListRadioButton.isSelected()) {
			for (Map.Entry<String, List<MavenArtifactNode>> s : allArtifactsMap.entrySet()) {
				if (searchFieldText == null || s.getKey().contains(searchFieldText)) {
					listDataModel.addElement(new MyListNode(s));
				}
			}
			showNoConflictsLabel = false;
			leftPanelTree.getParent().getParent().setVisible(false);
			leftPanelList.getParent().getParent().setVisible(true);
		} else { // tree
			fillLeftTree(leftTreeRoot, dependencyTree, searchFieldText);
			leftTreeModel.nodeStructureChanged(leftTreeRoot);
			expandAll(leftPanelTree, new TreePath(leftTreeRoot.getPath()));

			showNoConflictsLabel = false;
			leftPanelTree.getParent().getParent().setVisible(true);
			leftPanelList.getParent().getParent().setVisible(false);
		}

		if (conflictsWarning) {
			javax.swing.SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					noConflictsWarningLabelScrollPane.getVerticalScrollBar().setValue(0);
				}
			});
		}

		noConflictsWarningLabelScrollPane.setVisible(conflictsWarning);
		applyMavenVmOptionsFixButton.setVisible(conflictsWarning);
		noConflictsLabel.setVisible(showNoConflictsLabel);
	}

	private boolean fillLeftTree(DefaultMutableTreeNode parent, List<MavenArtifactNode> dependencyTree,
			String searchFieldText) {
		Collections.sort(dependencyTree, BY_ARTICATF_ID);
		boolean containsFilteredItem = false;
		for (MavenArtifactNode mavenArtifactNode : dependencyTree) {
			SimpleTextAttributes attributes = SimpleTextAttributes.REGULAR_ATTRIBUTES;
			MyTreeUserObject treeUserObject = new MyTreeUserObject(mavenArtifactNode, attributes);
			if (StringUtils.isNotBlank(searchFieldText)
					&& mavenArtifactNode.getArtifact().toString().contains(searchFieldText)) {
				containsFilteredItem = true;
				treeUserObject.highlight = true;
			}
			final DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(treeUserObject);
			containsFilteredItem |= fillLeftTree(newNode, mavenArtifactNode.getDependencies(), searchFieldText);

			if (parent == leftTreeRoot) {
				if (!containsFilteredItem && StringUtils.isNotBlank(searchFieldText)) {
					// do not add
				} else {
					parent.add(newNode);
				}
				containsFilteredItem = false;
			} else {
				parent.add(newNode);
			}
		}

		return containsFilteredItem;
	}

	private boolean hasConflicts(List<MavenArtifactNode> nodes) {
		String version = null;
		for (MavenArtifactNode node : nodes) {
			if (version != null && !version.equals(node.getArtifact().getVersion())) {
				return true;
			}
			version = node.getArtifact().getVersion();
		}
		return false;
	}

	private Map<String, List<MavenArtifactNode>> createAllArtifactsMap(List<MavenArtifactNode> dependencyTree) {
		final Map<String, List<MavenArtifactNode>> map = new TreeMap<String, List<MavenArtifactNode>>();
		addAll(map, dependencyTree, 0);
		return map;
	}

	private void addAll(Map<String, List<MavenArtifactNode>> map, List<MavenArtifactNode> artifactNodes, int i) {
		if (map == null) {
			return;
		}
		if (i > 100) {
			final StringBuilder stringBuilder = new StringBuilder();
			for (MavenArtifactNode s : artifactNodes) {
				final String s1 = s.getArtifact().toString();
				stringBuilder.append(s1);
				stringBuilder.append(" ");
			}
			LOG.error("Recursion aborted, artifactNodes = [" + stringBuilder + "]");
			return;
		}
		for (MavenArtifactNode mavenArtifactNode : artifactNodes) {
			final MavenArtifact artifact = mavenArtifactNode.getArtifact();

			final String key = getArtifactKey(artifact);
			final List<MavenArtifactNode> mavenArtifactNodes = map.get(key);
			if (mavenArtifactNodes == null) {
				final ArrayList<MavenArtifactNode> value = new ArrayList<MavenArtifactNode>(1);
				value.add(mavenArtifactNode);
				map.put(key, value);
			} else {
				mavenArtifactNodes.add(mavenArtifactNode);
			}
			addAll(map, mavenArtifactNode.getDependencies(), i + 1);
		}
	}

	@NotNull
	private String getArtifactKey(MavenArtifact artifact) {
		return artifact.getGroupId() + " : " + artifact.getArtifactId();
	}

	public JComponent getRootComponent() {
		return rootPanel;
	}

	public JComponent getPreferredFocusedComponent() {
		return rootPanel;
	}

	public void selectNotify() {
		initializeModel();
		splitPane.setDividerLocation(0.5);
	}

}
