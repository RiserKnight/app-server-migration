package com.amazon.aws.am2.appmig.glassviewer;

import com.amazon.aws.am2.appmig.glassviewer.constructs.*;
import com.amazon.aws.am2.appmig.glassviewer.db.AppDiscoveryGraphDB;
import com.amazon.aws.am2.appmig.glassviewer.db.IAppDiscoveryGraphDB;
import com.amazon.aws.am2.appmig.glassviewer.db.QueryBuilder;
import com.amazon.aws.am2.appmig.search.ISearch;
import com.amazon.aws.am2.appmig.search.RegexSearch;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static com.amazon.aws.am2.appmig.glassviewer.db.IAppDiscoveryGraphDB.*;
import static com.amazon.aws.am2.appmig.glassviewer.db.QueryBuilder.OP;
import static com.amazon.aws.am2.appmig.glassviewer.db.QueryBuilder.buildQuery;

/**
 * @author Aditya Goteti
 */
public class JavaGlassViewer extends AbstractJavaGlassViewer {

    private final static Logger LOGGER = LoggerFactory.getLogger(JavaGlassViewer.class);
    private ClassConstruct classConstruct;
    private List<InterfaceConstruct> interfaceConstructs;
    private final ISearch search = new RegexSearch();

    private int loc;

    @Override
    public void setBasePackage(String packageName) {
        basePackage = packageName;
    }

    @Override
    public void processClasses() {
        LOGGER.debug("processing classes");
        JavaClassConstructListener listener = new JavaClassConstructListener();
        ParseTreeWalker.DEFAULT.walk(listener, parseTree);
        classConstruct = listener.getClassConstruct();
        this.loc = classConstruct.getLOC();
        classConstruct.setAbsoluteFilePath(filePath);
    }

    public int getLoc() {
        return this.loc;
    }

    @Override
    public void processInterfaces() {
        LOGGER.debug("processing interfaces");
        JavaInterfaceConstructListener listener = new JavaInterfaceConstructListener();
        ParseTreeWalker.DEFAULT.walk(listener, parseTree);
        interfaceConstructs = listener.getInterfaceConstructs();
        for (InterfaceConstruct construct : interfaceConstructs) {
            construct.setAbsoluteFilePath(filePath);
            this.loc = this.loc + construct.getLoc();
            construct.setPackageName(classConstruct.getPackageName());
        }
    }

    @Override
    public void processMethods() {
        LOGGER.debug("processing methods");
        JavaMethodConstructListener listener = new JavaMethodConstructListener();
        ParseTreeWalker.DEFAULT.walk(listener, parseTree);
        List<MethodConstruct> methods = listener.getMethodConstructList();
        if (classConstruct.getName() != null)
            classConstruct.setMethods(methods);
        if (!interfaceConstructs.isEmpty() && interfaceConstructs.get(0).getName() != null)
            interfaceConstructs.get(0).setMethods(methods);
    }

    @Override
    public void processImports() {
        LOGGER.debug("processing imports");
        JavaImportConstructListener listener = new JavaImportConstructListener();
        ParseTreeWalker.DEFAULT.walk(listener, parseTree);
        List<ImportConstruct> imports = listener.getImportConstructList();
        if (classConstruct.getName() != null)
            classConstruct.setImports(imports);
        if (!interfaceConstructs.isEmpty() && interfaceConstructs.get(0).getName() != null)
            interfaceConstructs.get(0).setImports(imports);
    }

    @Override
    public void processClassVariables() {
        LOGGER.debug("processing class variables");
        JavaClassVariableConstructListener listener = new JavaClassVariableConstructListener();
        ParseTreeWalker.DEFAULT.walk(listener, parseTree);
        List<VariableConstruct> classVariablesList = listener.getClassVariableConstructList();
        if (classConstruct.getName() != null)
            classConstruct.setClassVariables(classVariablesList);
        if (!interfaceConstructs.isEmpty() && interfaceConstructs.get(0).getName() != null)
            interfaceConstructs.get(0).setClassVariables(classVariablesList);
    }

    @Override
    public void processStaticBlocks() {
        LOGGER.debug("processing static blocks");
        JavaStaticBlockConstructListener listener = new JavaStaticBlockConstructListener();
        ParseTreeWalker.DEFAULT.walk(listener, parseTree);
    }

    @Override
    public void store() {
        if (classConstruct.getName() != null) {
            IAppDiscoveryGraphDB db = AppDiscoveryGraphDB.getInstance();
            String packageId = storePackage(classConstruct.getPackageName());
            String fetch = new QueryBuilder.MatchBuilder().type(CLASS_COLLECTION).where(classConstruct.getFullClassName()).build();
            String classId = db.exists(fetch);
            if (classId == null || classId.isEmpty()) {
                LOGGER.debug("Creating the class node {}", classConstruct);
                classId = db.saveNode(buildQuery(classConstruct, QueryBuilder.OP.CREATE));
                // Establishing relationship between the class and its associated package
                db.saveNode(QueryBuilder.buildRelation(PROJECT_PACKAGE_EDGE, projectId, packageId));
                db.saveNode(QueryBuilder.buildRelation(PACKAGE_CLASS_EDGE, packageId, classId));
            } else {
                LOGGER.debug("Node already exists! updating the class node {} ", classConstruct);
                db.saveNode(QueryBuilder.buildQuery(classConstruct, OP.UPDATE));
            }

            List<ImportConstruct> imports = classConstruct.getImports();
            for (ImportConstruct importConstruct : imports) {
                LOGGER.debug("Creating the import node {}", importConstruct);
                if (basePackage != null && !basePackage.isEmpty() && importConstruct.getPackageName().startsWith(basePackage)) {
                    storeClassRelation(importConstruct, classId);
                } else {
                    String readImportNode = QueryBuilder.buildImportNode(importConstruct, OP.READ);
                    String importId;
                    importId = db.exists(readImportNode);
                    if (importId == null) {
                        importId = db.saveNode(QueryBuilder.buildImportNode(importConstruct, OP.CREATE));
                    }
                    db.saveNode(QueryBuilder.buildRelation(CLASS_IMPORTS_EDGE, classId, importId));
                }
            }

            List<MethodConstruct> methods = classConstruct.getMethods();
            for (MethodConstruct methodConstruct : methods) {
                LOGGER.debug("Creating method node {}", methodConstruct);
                String readMethodNode = QueryBuilder.buildMethodNode(classConstruct, methodConstruct, QueryBuilder.OP.READ);
                String methodId = db.exists(readMethodNode);
                if (methodId == null) {
                    methodId = db.saveNode(QueryBuilder.buildMethodNode(classConstruct, methodConstruct, QueryBuilder.OP.CREATE));
                    methodConstruct.setId(methodId);
                }
                db.saveNode(QueryBuilder.buildRelation(CLASS_METHOD_EDGE, classId, methodId));

                List<VariableConstruct> methodVariables = methodConstruct.getLocalVariables();
                for (VariableConstruct variableConstruct : methodVariables) {
                    LOGGER.debug("Creating the methodVariable node {}", variableConstruct);
                    String readMethodVariableNode = QueryBuilder.buildMethodVariableNode(classConstruct, methodConstruct, variableConstruct, QueryBuilder.OP.READ);
                    String methodVariableId = db.exists(readMethodVariableNode);
                    if (methodVariableId == null) {
                        methodVariableId = db.saveNode(QueryBuilder.buildMethodVariableNode(classConstruct, methodConstruct, variableConstruct, QueryBuilder.OP.CREATE));
                        if (methodVariableId == null) {
                            LOGGER.error("Error creating method variable {}", variableConstruct);
                        } else {
                            db.saveNode(QueryBuilder.buildRelation(METHOD_VARIABLE_EDGE, methodId, methodVariableId));
                        }
                    }
                }
            }
            List<VariableConstruct> classVariables = classConstruct.getClassVariables();
            for (VariableConstruct variableConstruct : classVariables) {
                LOGGER.debug("Creating the classVariable node {}", variableConstruct);
                String readClassVariableNode = QueryBuilder.buildClassVariableNode(classConstruct, variableConstruct, QueryBuilder.OP.READ);
                String variableId;
                variableId = db.exists(readClassVariableNode);
                if (variableId == null) {
                    variableId = db.saveNode(QueryBuilder.buildClassVariableNode(classConstruct, variableConstruct, QueryBuilder.OP.CREATE));
                    if (variableId == null) {
                        LOGGER.debug("Could not create ClassVariableNode");
                        continue;
                    }
                }
                String res = db.saveNode(QueryBuilder.buildRelation(CLASS_VARIABLE_EDGE, classId, variableId));
                if (res == null) {
                    LOGGER.debug("Could not create ClassVariableRelation");
                }
            }
        } else {
            if (interfaceConstructs != null && !interfaceConstructs.isEmpty()) {
                InterfaceConstruct interfaceConstruct = interfaceConstructs.get(0);
                IAppDiscoveryGraphDB db = AppDiscoveryGraphDB.getInstance();
                String packageId = storePackage(interfaceConstruct.getPackageName());
                String fetch = new QueryBuilder.MatchBuilder().type(CLASS_COLLECTION).where(interfaceConstruct.getFullClassName()).build();
                String interfaceId = db.exists(fetch);
                if (interfaceId == null || interfaceId.isEmpty()) {
                    LOGGER.debug("Creating the interface node {}", interfaceConstruct);
                    interfaceId = db.saveNode(QueryBuilder.buildQuery(interfaceConstruct, OP.CREATE));
                    //Package -> Interface relation
                    db.saveNode(QueryBuilder.buildRelation(PROJECT_PACKAGE_EDGE, projectId, interfaceId));
                    db.saveNode(QueryBuilder.buildRelation(PACKAGE_CLASS_EDGE, packageId, interfaceId));
                } else {
                    LOGGER.debug("Node already exists! updating the node {}", interfaceConstruct);
                    db.saveNode(QueryBuilder.buildQuery(interfaceConstruct, OP.UPDATE));
                }

                List<ImportConstruct> imports = interfaceConstruct.getImports();
                for (ImportConstruct importConstruct : imports) {
                    LOGGER.debug("Creating the interface import node {}", importConstruct);
                    if (basePackage != null && !basePackage.isEmpty()
                            && importConstruct.getPackageName().startsWith(basePackage)) {
                        storeClassRelation(importConstruct, interfaceId);
                    } else {
                        String readImportNode = QueryBuilder.buildImportNode(importConstruct, OP.READ);
                        String importId = db.exists(readImportNode);
                        if (importId == null) {
                            importId = db.saveNode(QueryBuilder.buildImportNode(importConstruct, OP.CREATE));
                        }
                        db.saveNode(QueryBuilder.buildRelation(CLASS_IMPORTS_EDGE, interfaceId, importId));
                    }
                }

                List<MethodConstruct> methods = interfaceConstruct.getMethods();
                for (MethodConstruct methodConstruct : methods) {
                    LOGGER.debug("Creating the method node in interface {}", methodConstruct);
                    String readMethodNode = QueryBuilder.buildMethodNode(interfaceConstruct, methodConstruct, QueryBuilder.OP.READ);
                    String methodId = db.exists(readMethodNode);
                    if (methodId == null) {
                        methodId = db.saveNode(QueryBuilder.buildMethodNode(interfaceConstruct, methodConstruct, QueryBuilder.OP.CREATE));
                    }
                    String res = db.saveNode(QueryBuilder.buildRelation(CLASS_METHOD_EDGE, interfaceId, methodId));
                    if (res != null) {
                        LOGGER.debug("cannot create ClassMethodRelation");
                    }
                }

                List<VariableConstruct> classVariables = interfaceConstruct.getClassVariables();
                for (VariableConstruct variableConstruct : classVariables) {
                    LOGGER.debug("Creating the interface classVariable node {}", variableConstruct);
                    String readClassVariableNode = QueryBuilder.buildClassVariableNode(interfaceConstruct, variableConstruct, QueryBuilder.OP.READ);
                    String variableId;
                    variableId = db.exists(readClassVariableNode);
                    if (variableId == null) {
                        variableId = db.saveNode(QueryBuilder.buildClassVariableNode(interfaceConstruct, variableConstruct, QueryBuilder.OP.CREATE));
                        if (variableId == null) {
                            LOGGER.debug("could not create ClassVariableNode");
                            continue;
                        }
                    }
                    String res = db.saveNode(QueryBuilder.buildRelation(CLASS_VARIABLE_EDGE, interfaceId, variableId));
                    if (res == null) {
                        LOGGER.debug("could not create ClassVariableRelation");
                    }
                }
            }
        }
    }

    public void storeClassRelation(ImportConstruct importConstruct, String classId) {
        ClassConstruct cc = new ClassConstruct();
        cc.setName(importConstruct.getClassName());
        cc.setPackageName(importConstruct.getPackageName());

        IAppDiscoveryGraphDB db = AppDiscoveryGraphDB.getInstance();
        String fetch = new QueryBuilder.MatchBuilder().type(CLASS_COLLECTION).where(cc.getFullClassName()).build();
        String newClassId = db.exists(fetch);
        if (newClassId == null || newClassId.isEmpty()) {
            LOGGER.debug("Creating the node {}", cc);
            String packageId = storePackage(cc.getPackageName());
            db.saveNode(QueryBuilder.buildRelation(PROJECT_PACKAGE_EDGE, projectId, packageId));
            newClassId = db.saveNode(buildQuery(cc, QueryBuilder.OP.CREATE));
            db.saveNode(QueryBuilder.buildRelation(PACKAGE_CLASS_EDGE, packageId, newClassId));
        }
        db.saveNode(QueryBuilder.buildRelation(CLASS_CLASS_EDGE, classId, newClassId));
    }

    public String storeProject(String projectName) {
        IAppDiscoveryGraphDB db = AppDiscoveryGraphDB.getInstance();
        ProjectConstruct pc = new ProjectConstruct();
        pc.setName(projectName);
        String readProjectNode = QueryBuilder.buildProjectNode(pc, OP.READ);
        String projectId = db.exists(readProjectNode);
        if (projectId == null) {
            projectId = db.saveNode(QueryBuilder.buildProjectNode(pc, OP.CREATE));
        }
        return projectId;
    }

    private String storePackage(String packageName) {
        IAppDiscoveryGraphDB db = AppDiscoveryGraphDB.getInstance();
        PackageConstruct pc = new PackageConstruct();
        int index = packageName.lastIndexOf('.');
        pc.setPackageName(packageName.substring(index > -1 ? index + 1 : 0));
        pc.setFullPackageName(packageName);
        String readImportNode = QueryBuilder.buildPackageNode(pc, OP.READ);
        String packageId = db.exists(readImportNode);
        if (packageId == null) {
            packageId = db.saveNode(QueryBuilder.buildPackageNode(pc, OP.CREATE));
        }
        return packageId;
    }

    @Override
    public Map<Integer, String> search(String pattern) throws Exception {
        Map<Integer, String> mapLineStatement = new HashMap<>();
        List<VariableConstruct> lstVariableConstruct = new ArrayList<>();
        if (classConstruct != null && classConstruct.getName() != null) {
            lstVariableConstruct.addAll(classConstruct.getClassVariables());
            classConstruct.getMethods().forEach(method -> lstVariableConstruct.addAll(method.getLocalVariables()));
        } else if (interfaceConstructs != null && !interfaceConstructs.isEmpty()) {
            interfaceConstructs.forEach(interfaceConstruct -> lstVariableConstruct.addAll(interfaceConstruct.getClassVariables()));
        }
        for (VariableConstruct variable : lstVariableConstruct) {
            String value = variable.getValue();
            if (search.find(pattern, value, true)) {
                mapLineStatement.put(variable.getMetaData().getStartsAt(), variable.getValue());
            }
        }
        return mapLineStatement;
    }

    /*
     * Checks for -
     * 1. Import statement in DB
     * 2. Class Variables in DB
     * 3. Methods declaration/local variables/statements/filteredClassVariables
     * */
    @Override
    public Map<Integer, String> searchReferences(String importStmt) throws Exception {
        Map<Integer, String> mapLineStatement = new HashMap<>();
        IAppDiscoveryGraphDB db = AppDiscoveryGraphDB.getInstance();
        if (classConstruct != null && classConstruct.getName() != null) {
            // check for import in DB for the specific Class
            List<String> matchingImports = db.existsRelation(QueryBuilder.getMatchingClassImport(classConstruct, importStmt));
            if (matchingImports == null || matchingImports.isEmpty()) {
                LOGGER.debug("Class \"{}\" does not contain import statement \"{}\"", classConstruct.getFullClassName(), importStmt);
                return mapLineStatement;
            }
            LOGGER.debug("Class \"{}\" contains import statement \"{}\" Processing further....\n", classConstruct.getFullClassName(), importStmt);
            // then check for class variables in DB
            searchInDB(classConstruct, matchingImports, importStmt, mapLineStatement);
            searchForAnnotations(classConstruct, matchingImports, mapLineStatement);
        }
        if (interfaceConstructs != null && !interfaceConstructs.isEmpty()) {
            // check for import in DB for the specific Class
            InterfaceConstruct interfaceConstruct = interfaceConstructs.get(0);
            List<String> matchingImports = db.existsRelation(QueryBuilder.getMatchingClassImport(interfaceConstruct, importStmt));
            if (matchingImports == null || matchingImports.isEmpty()) {
                LOGGER.debug("Interface \"{}\" does not contain import statement \"{}\"", interfaceConstruct.getFullClassName(), importStmt);
                return mapLineStatement;
            }
            LOGGER.debug("Interface \"{}\" contains import statement \"{}\" Processing further....\n", interfaceConstruct.getFullClassName(), importStmt);
            // then check for class variables in DB
            searchInDB(interfaceConstruct, matchingImports, importStmt, mapLineStatement);
            searchForAnnotations(interfaceConstruct, matchingImports, mapLineStatement);
        }
        return mapLineStatement;
    }

    private void searchForAnnotations(JavaConstruct construct, List<String> matchingImports, Map<Integer, String> mapLineStatement) {
        List<AnnotationConstruct> annotations = null;
        List<MethodConstruct> methods = null;
        if (construct instanceof ClassConstruct) {
            ClassConstruct cc = (ClassConstruct) construct;
            annotations = cc.getAnnotations();
            methods = cc.getMethods();
            cc.getClassVariables().forEach(variable -> {
                addAnnotations(variable.getVariableAnnotations(), matchingImports, mapLineStatement);
            });
        } else if (construct instanceof InterfaceConstruct) {
            annotations = ((InterfaceConstruct) construct).getAnnotations();
            methods = ((InterfaceConstruct) construct).getMethods();
        }
        if (annotations != null && !annotations.isEmpty()) {
            addAnnotations(annotations, matchingImports, mapLineStatement);
        }
        if (methods != null && !methods.isEmpty()) {
            for (MethodConstruct method : methods) {
                addAnnotations(method.getAnnotations(), matchingImports, mapLineStatement);
            }
        }
    }

    private void addAnnotations(List<AnnotationConstruct> annotations, List<String> matchingImports, Map<Integer, String> mapLineStatement) {
        if (annotations != null && !annotations.isEmpty()) {
            for (AnnotationConstruct annotation : annotations) {
                String value = annotation.getName().startsWith("@") ? annotation.getName().substring(1) : annotation.getName();
                if (matchingImports.stream().anyMatch(value::contains)) {
                    mapLineStatement.put(annotation.getMetaData().getStartsAt(), annotation.getName());
                }
            }
        }
    }

    private void searchInDB(JavaConstruct construct, List<String> matchingImports, String importStmt, Map<Integer, String> mapLineStatement) {
        IAppDiscoveryGraphDB db = AppDiscoveryGraphDB.getInstance();
        final List<String> result = new ArrayList<>();
        if (construct instanceof ClassConstruct) {
            ClassConstruct classConstruct = (ClassConstruct) construct;
            result.addAll(db.read(QueryBuilder.getMatchingClassVariableImport(classConstruct, matchingImports)));
            classConstruct.getMethods().forEach(method -> result.addAll(db.read(QueryBuilder.getMatchingMethodVariableImport(method, matchingImports))));
        }
        if (construct instanceof InterfaceConstruct) {
            InterfaceConstruct interfaceConstruct = (InterfaceConstruct) construct;
            result.addAll(db.read(QueryBuilder.getMatchingClassVariableImport(interfaceConstruct, matchingImports)));
        }
        List<VariableConstruct> variableConstructs = new ArrayList<>();
        if (!result.isEmpty()) {
            result.forEach(str -> {
                JSONObject json = new JSONObject(str);
                VariableConstruct cvc = new VariableConstruct();
                cvc.setName(json.getString("name"));
                cvc.setVariableType(json.getString("type"));
                cvc.setVariableModifiers(Arrays.asList(json.getString("modifiers").replaceAll("\\[|]", "").split(",")));
                cvc.getMetaData().setStartsAt(json.getInt("startsAt"));
                cvc.getMetaData().setEndsAt(json.getInt("endsAt"));
                variableConstructs.add(cvc);
            });
            List<String> filteredClassVariables = variableConstructs.stream().map(VariableConstruct::getName).collect(Collectors.toList());
            // then check for methods declaration/variable/statements/filteredClassVariables
            JavaSearchReferenceListener listener = new JavaSearchReferenceListener(importStmt, filteredClassVariables, matchingImports);
            ParseTreeWalker.DEFAULT.walk(listener, parseTree);
            Map<Integer, String> mapLineStmtFromMethods = listener.getMapLineStmt();
            mapLineStatement.putAll(mapLineStmtFromMethods);
        }
    }
}
