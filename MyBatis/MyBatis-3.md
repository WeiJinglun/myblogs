# SqlSession执行Mapper过程

## 1. Mapper 接口的注册过程

Mapper 接口用于定义执行 SQL 语句相关的方法，方法名一般和 Mapper XML 配置文件中 <select|update|delete|insert> 标签的 id 属性相同，接口的完全限定名一般对应 Mapper XML 配置文件的命名空间。

如何执行 Mapper 中定义的方法，可参考下面的代码：

```java
	@Test
    public  void testMybatis () throws IOException {
        // 获取配置文件输入流
        InputStream inputStream = Resources.getResourceAsStream("mybatis-config.xml");
        // 通过SqlSessionFactoryBuilder的build()方法创建SqlSessionFactory实例
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
        // 调用openSession()方法创建SqlSession实例
        SqlSession sqlSession = sqlSessionFactory.openSession();
        // 获取UserMapper代理对象
        UserMapper userMapper = sqlSession.getMapper(UserMapper.class);
        // 执行Mapper方法，获取执行结果
        List<UserEntity> userList = userMapper.listAllUser();
    }
```

如上面的代码所示，在创建 SqlSession 实例后，需要调用 SqlSession 的 getMapper() 方法获取一个 UserMapper 的引用，然后通过该引用调用 Mapper 接口中定义的方法。

接口中定义的方法必须通过某个类实现该接口，然后创建该类的实例，才能通过实例调用方法。所以 SqlSession 对象的 getMapper() 方法返回的是一个动态代理对象。

MyBatis 中通过 MapperProxy 类实现动态代理。下面是 MapperProxy 类的关键代码：

```java
public class MapperProxy<T> implements InvocationHandler, Serializable {

  private static final long serialVersionUID = -6424540398559729838L;
  private final SqlSession sqlSession;
  private final Class<T> mapperInterface;
  private final Map<Method, MapperMethod> methodCache;

  public MapperProxy(SqlSession sqlSession, Class<T> mapperInterface, Map<Method, MapperMethod> methodCache) {
    this.sqlSession = sqlSession;
    this.mapperInterface = mapperInterface;
    this.methodCache = methodCache;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      // 从Object类继承的方法不做处理
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, args);
      } else if (isDefaultMethod(method)) {
        return invokeDefaultMethod(proxy, method, args);
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
    // 对Mapper接口中定义的方法进行封装，生成MapperMethod对象
    final MapperMethod mapperMethod = cachedMapperMethod(method);
    return mapperMethod.execute(sqlSession, args);
  }
  ……
}
```

MapperProxy 使用的是 JDK 内置的动态代理，实现了 InvocationHandler 接口，invoke() 方法中为通用的拦截逻辑，还需要调用 java.lang.reflect.Proxy 类的 newProxyInstance() 方法创建代理对象。

MyBatis 对这一过程做了封装，使用 MapperProxyFactory 创建 Mapper 动态代理对象。MapperProxyFactory 代码如下：

```java
public class MapperProxyFactory<T> {

  private final Class<T> mapperInterface;
  private final Map<Method, MapperMethod> methodCache = new ConcurrentHashMap<Method, MapperMethod>();

  public MapperProxyFactory(Class<T> mapperInterface) {
    this.mapperInterface = mapperInterface;
  }

  public Class<T> getMapperInterface() {
    return mapperInterface;
  }

  public Map<Method, MapperMethod> getMethodCache() {
    return methodCache;
  }

  @SuppressWarnings("unchecked")
  protected T newInstance(MapperProxy<T> mapperProxy) {
    return (T) Proxy.newProxyInstance(mapperInterface.getClassLoader(), new Class[] { mapperInterface }, mapperProxy);
  }

  public T newInstance(SqlSession sqlSession) {
    final MapperProxy<T> mapperProxy = new MapperProxy<T>(sqlSession, mapperInterface, methodCache);
    return newInstance(mapperProxy);
  }

}
```

如上面的代码所示，MapperProxyFactory 类的工厂方法 newInstance() 是非静态的。也就是说，使用 MapperProxyFactory 创建 Mapper 动态代理对象首先需要创建 MapperProxyFactory 实例。\

Configuration对象中有一个mapperRegistry属性，具体如下：

```java
public class Configuration {
    ……
	protected final MapperRegistry mapperRegistry = new MapperRegistry(this);
    ……
}
```

MyBatis 通过 mapperRegistry 属性注册 Mapper 接口与 MapperProxyFactory 对象之间的对应关系。下面是 MapperRegistry类的关键代码：

```java
public class MapperRegistry {
  // Configuration对象引用
  private final Configuration config;
  // 用于注册Mapper接口Class对象，和MapperProxyFactory对象对应关系
  private final Map<Class<?>, MapperProxyFactory<?>> knownMappers = new HashMap<Class<?>, MapperProxyFactory<?>>();

  public MapperRegistry(Configuration config) {
    this.config = config;
  }
  // 根据Mapper接口Class对象获取Mapper动态代理对象
  @SuppressWarnings("unchecked")
  public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
    final MapperProxyFactory<T> mapperProxyFactory = (MapperProxyFactory<T>) knownMappers.get(type);
    if (mapperProxyFactory == null) {
      throw new BindingException("Type " + type + " is not known to the MapperRegistry.");
    }
    try {
      return mapperProxyFactory.newInstance(sqlSession);
    } catch (Exception e) {
      throw new BindingException("Error getting mapper instance. Cause: " + e, e);
    }
  }
  
  public <T> boolean hasMapper(Class<T> type) {
    return knownMappers.containsKey(type);
  }
  // 根据Mapper接口Class对象，创建MapperProxyFactory对象，并注册到knownMappers属性中
  public <T> void addMapper(Class<T> type) {
    if (type.isInterface()) {
      if (hasMapper(type)) {
        throw new BindingException("Type " + type + " is already known to the MapperRegistry.");
      }
      boolean loadCompleted = false;
      try {
        knownMappers.put(type, new MapperProxyFactory<T>(type));
        // It's important that the type is added before the parser is run
        // otherwise the binding may automatically be attempted by the
        // mapper parser. If the type is already known, it won't try.
        MapperAnnotationBuilder parser = new MapperAnnotationBuilder(config, type);
        parser.parse();
        loadCompleted = true;
      } finally {
        if (!loadCompleted) {
          knownMappers.remove(type);
        }
      }
    }
  }
  ……
}
```

如上面的代码所示，MapperRegistry 类有一个 knownMappers 属性，用于注册 Mapper 接口对应的 Class 对象和 MapperProxyFactory 对象之间的关系。另外，MapperRegistry 提供了 addMapper() 方法，用于向 knownMappers 属性中注册 Mapper 接口信息。在 addMapper() 方法中，为每个 Mapper 接口对应的 Class 对象创建一个 MapperProxyFactory 对象，然后添加到 knownMappers 属性中。

MapperRegistry 还提供了 getMapper() 方法，能够根据 Mapper 接口的 Class 对象获取对应的 MapperProxyFactory 对象，然后就可以使用 MapperProxyFactory 对象创建 Mapper 动态代理对象了。

MyBatis 框架在应用启动时会解析所有的 Mapper 接口，然后调用 MapperRegistry 对象的 addMapper() 方法将 Mapper 接口信息和对应的 MapperProxyFactory 对象注册到 MapperRegistry 对象中。 

## 2. MappedStatement 注册过程

MyBatis 通过 MappedStatement 类描述 Mapper 的 SQL 配置信息。SQL 配置有两种方式：一种是通过 XML 文件配置；另一种是通过 Java 注解，而 Java 注解的本质就是一种轻量级的配置信息。

Configuration 类中有一个 mappedStatements 属性，该属性用于注册 MyBatis 中所有的 MappedStatement 对象，代码如下：

```java
public class Configuration {
    ……
    protected final Map<String, MappedStatement> mappedStatements = new StrictMap<MappedStatement>("Mapped Statements collection");
	……
}
```

mappedStatements 属性是一个 Map 对象，它的 Key 为 Mapper SQL 配置的 Id，如果 SQL 是通过 XML 配置的，则 Id 为命名空间加上 <select|update|delete|insert> 标签的 Id，如果 SQL 通过 Java 注解配置，则 Id 为 Mapper 接口的完全限定名（包括包名）加上方法名称。

另外，Configuration类中提供了一个addMappedStatement()方法，用于将MappedStatement对象添加到mappedStatements属性中，代码如下：

```java
  public void addMappedStatement(MappedStatement ms) {
    mappedStatements.put(ms.getId(), ms);
  }
```

**MappedStatement对象的创建及注册过程**

MyBatis 主配置文件的解析是通过 XMLConfigBuilder 对象来完成的。在 XMLConfigBuilder 类的 parseConfiguration() 方法中会调用不同的方法解析对应的标签。parseConfiguration() 代码如下：

```java
private void parseConfiguration(XNode root) {
    try {
      //issue #117 read properties first
      propertiesElement(root.evalNode("properties"));
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      loadCustomVfs(settings);
      typeAliasesElement(root.evalNode("typeAliases"));
      pluginElement(root.evalNode("plugins"));
      objectFactoryElement(root.evalNode("objectFactory"));
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      environmentsElement(root.evalNode("environments"));
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      typeHandlerElement(root.evalNode("typeHandlers"));
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }
```

想要了解 MappedStatement 对象的创建过程，就必须重点关注 \<mappers> 标签的解析过程。\<mappers> 标签是通过 XMLConfigBuilder 类的 mapperElement() 方法来解析的。下面是 mapperElement() 方法的实现：

```java
private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 通过<package>标签指定包名
        if ("package".equals(child.getName())) {
          String mapperPackage = child.getStringAttribute("name");
          configuration.addMappers(mapperPackage);
        } else {
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          // 通过resource属性指定XML文件路径
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            InputStream inputStream = Resources.getResourceAsStream(resource);
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            mapperParser.parse();
          } else if (resource == null && url != null && mapperClass == null) {
            // 通过url属性指定XML文件路径
            ErrorContext.instance().resource(url);
            InputStream inputStream = Resources.getUrlAsStream(url);
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            mapperParser.parse();
          } else if (resource == null && url == null && mapperClass != null) {
            // 通过class属性指定接口的完全限定名
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }
```

如上面的代码所示，在 mapperElement() 方法中，首先获取 \<mappers> 所有子标签（\<mapper> 标签或 \<package> 标签），然后根据不同的标签做不同的处理。\<mappers> 标签配置 Mapper 信息有以下几种方式：

```xml
    <mappers>
        <!-- 通过 resource 属性指定 Mapper 文件的 classpath 路径 -->
        <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
        <!-- 通过 url 属性指定 Mapper 文件网络路径 -->
        <mapper url="file:///var/mappers/BlogMapper.xml"/>
        <!-- 通过 class 属性指定 Mapper 接口的完全限定名 -->
        <mapper class="org.mybatis.builder.PostMapper"/>
        <!-- 通过 package 标签指定 Mapper 接口所在包名 -->
        <package name="org.mybatis.builder"/>
    </mappers>
```

mapperElement() 方法中对这几种情形的配置分别做了处理。接下来以 \<mapperresource="……”/> 这种形式为例介绍 Mapper SQL 配置文件的解析过程。

Mapper SQL 配置文件的解析需要借助 XMLMapperBuilder 对象。在 mapperElement() 方法中，首先创建一个 XMLMapperBuilder 对象，然后调用 XMLMapperBuilder 对象的 parse() 方法完成解析，该方法内容如下：

```java
public void parse() {
    if (!configuration.isResourceLoaded(resource)) {
      // 调用 XPathParser 的 evalNode（）方法获取根节点对应的 XNode 对象
      configurationElement(parser.evalNode("/mapper"));
      // 將资源路径添加到 Configuration 对象中
      configuration.addLoadedResource(resource);
      bindMapperForNamespace();
    }
    // 继续解析ResultMap对象
    parsePendingResultMaps();
    // 继续解析CacheRef对象
    parsePendingCacheRefs();
    // 继续解析<select|update|delete|insert>标签配置
    parsePendingStatements();
  }
```

上面的代码中，首先调用 XPathParser 对象的 evalNode() 方法获取根节点对应的 XNode 对象，接着调用 configurationElement() 方法对 Mapper 配置内容做进一步解析。下面是 configurationElement() 方法的内容：

```java
private void configurationElement(XNode context) {
    try {
      // 获取命名空间
      String namespace = context.getStringAttribute("namespace");
      if (namespace == null || namespace.equals("")) {
        throw new BuilderException("Mapper's namespace cannot be empty");
      }
      // 设置当前正在解析的Mapper配置的命名空间
      builderAssistant.setCurrentNamespace(namespace);
      // 解析<cache-ref>标签
      cacheRefElement(context.evalNode("cache-ref"));
      // 解析<cache>标签
      cacheElement(context.evalNode("cache"));
      // 解析所有的<parameterMap>标签
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
      // 解析所有的<resultMap>标签
      resultMapElements(context.evalNodes("/mapper/resultMap"));
      // 解析所有的<sql>标签
      sqlElement(context.evalNodes("/mapper/sql"));
      // 解析所有的<select|insert|update|delete>标签
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
    }
  }
```

如上面的代码所示，在 configurationElement() 方法中，对 Mapper SQL 配置文件的所有标签进行解析。这里我们重点关注 <select|insert|update|delete> 标签的解析。在上面的代码中，获取 <select|insert|update|delete> 标签节点对应的 XNode 对象后，调用 XMLMapperBuilder 类的 buildStatementFromContext() 方法做进一步解析处理。buildStatementFromContext() 方法的实现如下：

```java
  private void buildStatementFromContext(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    buildStatementFromContext(list, null);
  }

  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      // 通过XMLStatementBuilder对象，对<select|update|insert|delete>标签进行解析
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
        // 调用parseStatementNode（）方法解析
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }
```

如上面的代码所示，<select|insert|update|delete> 标签的解析需要依赖于 XMLStatementBuilder 对象，XMLMapperBuilder 类的 buildStatementFromContext() 方法中对所有 XNode 对象进行遍历，然后为每个 <select|insert|update|delete> 标签对应的 XNode 对象创建一个 XMLStatementBuilder 对象，接着调用XMLStatementBuilder 对象的 parseStatementNode() 方法进行解析处理。下面是 XMLStatementBuilder 类的 parseStatementNode() 方法的内容：

```java
public void parseStatementNode() {
    String id = context.getStringAttribute("id");
    String databaseId = context.getStringAttribute("databaseId");

    if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
      return;
    }
    // 解析<select|update|delete|insert>标签属性
    Integer fetchSize = context.getIntAttribute("fetchSize");
    Integer timeout = context.getIntAttribute("timeout");
    String parameterMap = context.getStringAttribute("parameterMap");
    String parameterType = context.getStringAttribute("parameterType");
    Class<?> parameterTypeClass = resolveClass(parameterType);
    String resultMap = context.getStringAttribute("resultMap");
    String resultType = context.getStringAttribute("resultType");
    // 获取LanguageDriver对象
    String lang = context.getStringAttribute("lang");
    LanguageDriver langDriver = getLanguageDriver(lang);
    // 获取Mapper返回结果类型Class对象
    Class<?> resultTypeClass = resolveClass(resultType);
    String resultSetType = context.getStringAttribute("resultSetType");
    // 默认Statement类型为PREPARED
    StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType",
            StatementType.PREPARED.toString()));
    ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);

    String nodeName = context.getNode().getNodeName();
    SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
    boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
    boolean useCache = context.getBooleanAttribute("useCache", isSelect);
    boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

    // 將<include>标签内容，替换为<sql>标签定义的SQL片段
    XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
    includeParser.applyIncludes(context.getNode());

    // 解析<selectKey>标签
    processSelectKeyNodes(id, parameterTypeClass, langDriver);
    
    // 通过LanguageDriver解析SQL内容，生成SqlSource对象
    SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
    String resultSets = context.getStringAttribute("resultSets");
    String keyProperty = context.getStringAttribute("keyProperty");
    String keyColumn = context.getStringAttribute("keyColumn");
    KeyGenerator keyGenerator;
    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
    // 获取主键生成策略
    if (configuration.hasKeyGenerator(keyStatementId)) {
      keyGenerator = configuration.getKeyGenerator(keyStatementId);
    } else {
      keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
          configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
          ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
    }

    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered, 
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
  }
```

如上面的代码所示，XMLStatementBuilder 类的 parseStatementNode() 方法的内容相对较多，但是逻辑非常清晰，主要做了以下几件事情：

1.  获取 \<select|insert|delete|update> 标签的所有属性信息。
2.  将 \<include >标签引用的 SQL 片段替换为对应的 \<sql> 标签中定义的内容。
3.  获取 lang 属性指定的 LanguageDriver，通过 LanguageDriver 创建 SqlSource。
4.  获取 KeyGenerator 对象。KeyGenerator 的不同实例代表不同的主键生成策略。
5.  所有解析工作完成后，使用 MapperBuilderAssistant 对象的 addMappedStatement() 方法创建 MappedStatement 对象。创建完成后，调用 Configuration 对象的 addMappedStatement() 方法将 MappedStatement 对象注册到 Configuration 对象中。

>   注意：MyBatis 中的 MapperBuilderAssistant 是一个辅助工具类，用于构建 Mapper 相关的对象，例如 Cache、ParameterMap、ResultMap 等。

## 3. Mapper 方法调用过程

为了执行 Mapper 接口中定义的方法，我们首先需要调用 SqlSession 对象的 getMapper() 方法获取一个动态代理对象，然后通过代理对象调用方法即可，代码如下：

```java
@Test
    public  void testMybatisCache () throws IOException {
        // 获取配置文件输入流
        InputStream inputStream = Resources.getResourceAsStream("mybatis-config.xml");
        // 通过SqlSessionFactoryBuilder的build()方法创建SqlSessionFactory实例
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
        // 调用openSession()方法创建SqlSession实例
        SqlSession sqlSession = sqlSessionFactory.openSession();
        // 获取UserMapper代理对象
        UserMapper userMapper = sqlSession.getMapper(UserMapper.class);
        // 执行Mapper方法，获取执行结果
        List<UserEntity> userList = userMapper.listAllUser();
    }
```

MyBatis 中的 MapperProxy 实现了 InvocationHandler 接口，当我们调用动态代理对象方法的时候，会执行 MapperProxy 类的 invoke() 方法。该方法的内容如下：

```java
public class MapperProxy<T> implements InvocationHandler, Serializable {
  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      // 从Object类继承的方法不做处理
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, args);
      } else if (isDefaultMethod(method)) {
        return invokeDefaultMethod(proxy, method, args);
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
    // 对Mapper接口中定义的方法进行封装，生成MapperMethod对象
    final MapperMethod mapperMethod = cachedMapperMethod(method);
    return mapperMethod.execute(sqlSession, args);
  }
……
}
```

如上面的代码所示，在 MapperProxy 类的 invoke() 方法中，对从 Object 类继承的方法不做任何处理，对 Mapper 接口中定义的方法，调用 cachedMapperMethod() 方法获取一个 MapperMethod 对象。cachedMapperMethod() 方法的内容如下：

```java
private MapperMethod cachedMapperMethod(Method method) {
    MapperMethod mapperMethod = methodCache.get(method);
    if (mapperMethod == null) {
      mapperMethod = new MapperMethod(mapperInterface, method, sqlSession.getConfiguration());
      methodCache.put(method, mapperMethod);
    }
    return mapperMethod;
  }
```

如上面的代码所示，cachedMapperMethod() 方法中对 MapperMethod 对象做了缓存，首先从缓存中获取，如果获取不到，则创建 MapperMethod 对象，然后添加到缓存中，这是享元思想的应用，避免频繁创建和回收对象。

上面代码中的 MapperMethod 类是对 Mapper 方法相关信息的封装，通过 MapperMethod 能够很方便地获取 SQL 语句的类型、方法的签名信息等。下面是 MapperMethod 类的构造方法：

```java
public class MapperMethod {

    private final SqlCommand command;
    private final MethodSignature method;

    public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
        this.command = new SqlCommand(config, mapperInterface, method);
        this.method = new MethodSignature(config, mapperInterface, method);
    }
	……
}
```

如上面的代码所示，在 MapperMethod 构造方法中创建了一个 SqlCommand 对象和一个 MethodSignature 对象：SqlCommand 对象用于获取 SQL 语句的类型、Mapper 的 Id 等信息；MethodSignature 对象用于获取方法的签名信息，例如 Mapper 方法的参数名、参数注解等信息。SqlCommand 类的构造方法，代码如下：

```java
 public static class SqlCommand {

        private final String name; // Mapper Id
        private final SqlCommandType type; // SQL类型

        public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
            final String methodName = method.getName();
            // 获取声明该方法的类或接口的Class对象
            final Class<?> declaringClass = method.getDeclaringClass();
            // 获取描述<select|update|insert|delete>标签的MappedStatement对象
            MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass,
                    configuration);
            if (ms == null) {
                if (method.getAnnotation(Flush.class) != null) {
                    name = null;
                    type = SqlCommandType.FLUSH;
                } else {
                    throw new BindingException("Invalid bound statement (not found): "
                            + mapperInterface.getName() + "." + methodName);
                }
            } else {
                name = ms.getId();
                type = ms.getSqlCommandType();
                if (type == SqlCommandType.UNKNOWN) {
                    throw new BindingException("Unknown execution method for: " + name);
                }
            }
        }
	……
 }
```

如上面的代码所示，在 SqlCommand 构造方法中调用 resolveMappedStatement() 方法，根据 Mapper 接口的完全限定名和方法名获取对应的 MappedStatement 对象，然后通过 MappedStatement 对象获取 SQL 语句的类型和 Mapper 的 Id。下面是 SqlCommand 类 resolveMappedStatement() 方法的实现：

```java
		private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName, Class<?> declaringClass, Configuration configuration) {
            // 获取Mapper的Id
            String statementId = mapperInterface.getName() + "." + methodName;
            if (configuration.hasStatement(statementId)) {
                // 如果Configuration对象中已经注册了MappedStatement对象，则获取该MappedStatement对象
                return configuration.getMappedStatement(statementId);
            } else if (mapperInterface.equals(declaringClass)) {
                return null;
            }
            // 如果方法是在Mapper父接口中定义，则根据父接口获取对应的MappedStatement对象
            for (Class<?> superInterface : mapperInterface.getInterfaces()) {
                if (declaringClass.isAssignableFrom(superInterface)) {
                    MappedStatement ms = resolveMappedStatement(superInterface, methodName,
                            declaringClass, configuration);
                    if (ms != null) {
                        return ms;
                    }
                }
            }
            return null;
        }
```

在上面的代码中，首先将接口的完全限定名和方法名进行拼接，作为 Mapper 的 Id 从 Configuration 对象中查找对应的 MappedStatement 对象，如果查找不到，则判断该方法是否是从父接口中继承的，如果是，就以父接口作为参数递归调用 resolveMappedStatement() 方法，若找到对应的 MappedStatement 对象，则返回该对象，否则返回 null。

SqlCommand 对象封装了 SQL 语句的类型和 Mapper 的 Id。下面是 MethodSignature 类的构造方法：

```java
        public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
            // 获取方法返回值类型
            Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
            if (resolvedReturnType instanceof Class<?>) {
                this.returnType = (Class<?>) resolvedReturnType;
            } else if (resolvedReturnType instanceof ParameterizedType) {
                this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
            } else {
                this.returnType = method.getReturnType();
            }
            // 返回值类型为void
            this.returnsVoid = void.class.equals(this.returnType);
            // 返回值类型为集合
            this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();
            // 返回值类型为Cursor
            this.returnsCursor = Cursor.class.equals(this.returnType);
            // 返回值类型为Optional
            this.returnsOptional = Jdk.optionalExists && Optional.class.equals(this.returnType);
            this.mapKey = getMapKey(method);
            // 返回值类型为Map
            this.returnsMap = this.mapKey != null;
            // RowBounds参数位置索引
            this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
            // ResultHandler参数位置索引
            this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);
            // ParamNameResolver用于解析Mapper方法参数
            this.paramNameResolver = new ParamNameResolver(configuration, method);
        }
```

如上面的代码所示，MethodSignature 构造方法中只做了 3 件事情：

1.  获取 Mapper 方法的返回值类型，具体是哪种类型，通过 boolean 类型的属性进行标记。例如，当返回值类型为 void 时，returnsVoid 属性值为 true，当返回值类型为 List 时，将 returnsMap 属性值设置为 true。MethodSignature 类中标记 Mapper 返回值类型的属性如下：

```java
public static class MethodSignature {

        private final boolean returnsMany;
        private final boolean returnsMap;
        private final boolean returnsVoid;
        private final boolean returnsCursor;
        private final boolean returnsOptional;
        private final Class<?> returnType;
    ……
}
```

2.  记录 RowBounds 参数位置，用于处理后续的分页查询，同时记录 ResultHandler 参数位置，用于处理从数据库中检索的每一行数据。
3.  创建 ParamNameResolver 对象。ParamNameResolver 对象用于解析 Mapper 方法中的参数名称及参数注解信息。

ParamNameResolver 构造方法中完成了 Mapper 方法参数的解析过程，代码如下：

```java
private final SortedMap<Integer, String> names;
public ParamNameResolver(Configuration config, Method method) {
    // 获取所有参数类型
    final Class<?>[] paramTypes = method.getParameterTypes();
    // 获取所有参数注解
    final Annotation[][] paramAnnotations = method.getParameterAnnotations();
    final SortedMap<Integer, String> map = new TreeMap<Integer, String>();
    int paramCount = paramAnnotations.length;
    // 从@Param 注解中获取参数名称
    for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
      if (isSpecialParameter(paramTypes[paramIndex])) {
        continue;
      }
      String name = null;
      for (Annotation annotation : paramAnnotations[paramIndex]) {
        // 方法参数中，是否有Param注解
        if (annotation instanceof Param) {
          hasParamAnnotation = true;
          // 获取参数名称
          name = ((Param) annotation).value();
          break;
        }
      }
      if (name == null) {
        // 未指定@Param 注解,这判断是否使用实际的参数名称，参考useActualParamName属性的作用
        if (config.isUseActualParamName()) {
          // 获取参数名
          name = getActualParamName(method, paramIndex);
        }
        if (name == null) {
          name = String.valueOf(map.size());
        }
      }
      // 將参数信息存放在Map中，Key为参数位置索引，Value为参数名称
      map.put(paramIndex, name);
    }
    // 將参数信息保存在names属性中
    names = Collections.unmodifiableSortedMap(map);
  }
```

如上面的代码所示，在 ParamNameResolver 构造方法中，对所有 Mapper 方法的所有参数信息进行遍历，首先判断参数中是否有 @Param 注解，如果包含 @Param 注解，就从注解中获取参数名称，如果参数中没有 @Param 注解，就根据 MyBatis 主配置文件中的 useActualParamName 参数确定是否获取实际方法定义的参数名称，若 useActualParamName 参数值为 true，则使用方法定义的参数名称。解析完毕后，将参数信息保存在一个不可修改的 names 属性中，该属性是一个 SortedMap<Integer, String> 类型的对象。

到此为止，整个 MapperMethod 对象的创建过程已经完成。

----

MapperMethod 提供了一个 execute() 方法，用于执行 SQL 命令。

```java
public class MapperProxy<T> implements InvocationHandler, Serializable {  
  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      // 从Object类继承的方法不做处理
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, args);
      } else if (isDefaultMethod(method)) {
        return invokeDefaultMethod(proxy, method, args);
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
    // 对Mapper接口中定义的方法进行封装，生成MapperMethod对象
    final MapperMethod mapperMethod = cachedMapperMethod(method);
    return mapperMethod.execute(sqlSession, args);
  }
  ……
}
```

如上面的代码所示，在 MapperProxy 类的 invoke() 方法中获取 MapperMethod 对象后，最终会调用 MapperMethod 类的 execute()。

```java
public class MapperMethod {
    private final SqlCommand command;
    private final MethodSignature method;
   
    public Object execute(SqlSession sqlSession, Object[] args) {
        Object result;
        // 其中command为MapperMethod构造时创建的SqlCommand对象
        // 获取SQL语句类型
        switch (command.getType()) {
            case INSERT: {
                // 获取参数信息
                Object param = method.convertArgsToSqlCommandParam(args);
                // 调用SqlSession的insert（）方法，然后调用rowCountResult（）方法统计行数
                result = rowCountResult(sqlSession.insert(command.getName(), param));
                break;
            }
            case UPDATE: {
                Object param = method.convertArgsToSqlCommandParam(args);
                // 调用SqlSession对象的update（）方法
                result = rowCountResult(sqlSession.update(command.getName(), param));
                break;
            }
            case DELETE: {
                Object param = method.convertArgsToSqlCommandParam(args);
                result = rowCountResult(sqlSession.delete(command.getName(), param));
                break;
            }
            case SELECT:
                if (method.returnsVoid() && method.hasResultHandler()) {
                    executeWithResultHandler(sqlSession, args);
                    result = null;
                } else if (method.returnsMany()) {
                    result = executeForMany(sqlSession, args);
                } else if (method.returnsMap()) {
                    result = executeForMap(sqlSession, args);
                } else if (method.returnsCursor()) {
                    result = executeForCursor(sqlSession, args);
                } else {
                    Object param = method.convertArgsToSqlCommandParam(args);
                    result = sqlSession.selectOne(command.getName(), param);
                    if (method.returnsOptional() &&
                            (result == null || !method.getReturnType().equals(result.getClass()))) {
                        result = OptionalUtil.ofNullable(result);
                    }
                }
                break;
            case FLUSH:
                result = sqlSession.flushStatements();
                break;
            default:
                throw new BindingException("Unknown execution method for: " + command.getName());
        }
        if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
            throw new BindingException("Mapper method '" + command.getName()
                    + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
        }
        return result;
    }
    ……
}
```

如上面的代码所示，在 execute() 方法中，首先根据 SqlCommand 对象获取 SQL 语句的类型，然后根据 SQL 语句的类型调用 SqlSession 对象对应的方法。例如，当 SQL 语句类型为 INSERT 时，通过 SqlCommand 对象获取 Mapper 的 Id，然后调用 SqlSession 对象的 insert() 方法。MyBatis 通过动态代理将 Mapper 方法的调用转换成通过 SqlSession 提供的 API 方法完成数据库的增删改查操作，即旧的 iBatis 框架调用 Mapper 的方式。

## 4. SqlSession 执行 Mapper 过程

MyBatis 通过动态代理将 Mapper 方法的调用转换为调用 SqlSession 提供的增删改查方法，以 Mapper 的 Id 作为参数，执行数据库的增删改查操作，即：

```java
	@Test
    public  void testMybatis () throws IOException {
        // 获取配置文件输入流
        InputStream inputStream = Resources.getResourceAsStream("mybatis-config.xml");
        // 通过SqlSessionFactoryBuilder的build()方法创建SqlSessionFactory实例
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
        // 调用openSession()方法创建SqlSession实例
        SqlSession sqlSession = sqlSessionFactory.openSession();
        // 兼容Ibatis，通过Mapper Id执行SQL操作
        List<UserEntity> userList = sqlSession.selectList("com.blog4java.mybatis.com.blog4java.mybatis.example.mapper.UserMapper.listAllUser");
        System.out.println(JSON.toJSONString(userList));
    }
```

以 SELECT 语句为例查看 SqlSession 执行 Mapper 的过程。SqlSession 接口只有一个默认的实现，即 DefaultSqlSession。下面是 DefaultSqlSession 类对 SqlSession 接口中定义的 selectList() 方法的实现：

```java
@Override
  public <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds) {
    try {
      // 根据Mapper的Id，获取对应的MappedStatement对象
      MappedStatement ms = configuration.getMappedStatement(statement);
      // 以MappedStatement对象作为参数，调用Executor的query（）方法
      return executor.query(ms, wrapCollection(parameter), rowBounds, Executor.NO_RESULT_HANDLER);
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }
```

如上面的代码所示，在 DefaultSqlSession 的 selectList() 方法中，首先根据 Mapper 的 Id 从 Configuration 对象中获取对应的 MappedStatement 对象，然后以 MappedStatement 对象作为参数，调用 Executor 实例的 query() 方法完成查询操作。下面是 BaseExecutor 类对 query() 方法的实现：

```java
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
    // 获取BoundSql对象，BoundSql是对动态SQL解析生成的SQL语句和参数映射信息的封装
    BoundSql boundSql = ms.getBoundSql(parameter);
    // 创建CacheKey，用于缓存Key
    CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
    // 调用重载的query（）方法
    return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
 }
```

在 BaseExecutor 类的 query() 方法中，首先从 MappedStatement 对象中获取 BoundSql 对象，BoundSql 类中封装了经过解析后的 SQL 语句及参数映射信息。然后创建 CacheKey 对象，该对象用于缓存的 Key 值。接着调用重载的query()方法。

```java
@Override
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    if (queryStack == 0 && ms.isFlushCacheRequired()) {
      clearLocalCache();
    }
    List<E> list;
    try {
      queryStack++;
      // 从缓存中获取结果
      list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
      if (list != null) {
        handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
      } else {
        // 缓存中获取不到，则调用queryFromDatabase（）方法从数据库中查询
        list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
      }
    } finally {
      queryStack--;
    }
    if (queryStack == 0) {
      for (DeferredLoad deferredLoad : deferredLoads) {
        deferredLoad.load();
      }
      // issue #601
      deferredLoads.clear();
      if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
        // issue #482
        clearLocalCache();
      }
    }
    return list;
  }
```

在重载的 query() 方法中，首先从 MyBatis 一级缓存中获取查询结果，如果缓存中没有，则调用 BaseExecutor 类的 queryFromDatabase() 方法从数据库中查询。

```java
private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    List<E> list;
    localCache.putObject(key, EXECUTION_PLACEHOLDER);
    try {
      // 调用doQuery（）方法查询
      list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
    } finally {
      localCache.removeObject(key);
    }
    // 缓存查询结果
    localCache.putObject(key, list);
    if (ms.getStatementType() == StatementType.CALLABLE) {
      localOutputParameterCache.putObject(key, parameter);
    }
    return list;
  }
```

如上面的代码所示，在 queryFromDatabase() 方法中，调用 doQuery() 方法进行查询，然后将查询结果进行缓存，doQuery() 是一个模板方法，由 BaseExecutor 子类实现。在学习 MyBatis 核心组件时，我们了解到 Executor 有几个不同的实现，分别为 BatchExecutor、SimpleExecutor 和 ReuseExecutor。SimpleExecutor 对 doQuery() 方法的代码如下：

```java
  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
    Statement stmt = null;
    try {
      Configuration configuration = ms.getConfiguration();
      // 获取StatementHandler对象
      StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
      // 调用prepareStatement（）方法,创建Statement对象，并进行设置参数等操作
      stmt = prepareStatement(handler, ms.getStatementLog());
      // 调用StatementHandler对象的query（）方法执行查询操作
      return handler.<E>query(stmt, resultHandler);
    } finally {
      closeStatement(stmt);
    }
  }
```

如上面的代码所示，在 SimpleExecutor 类的 doQuery() 方法中，首先调用 Configuration 对象的 newStatementHandler() 方法创建 StatementHandler 对象。newStatementHandler() 方法返回的是 RoutingStatementHandler 的实例。在 RoutingStatementHandler 类中，会根据配置 Mapper 时statementType 属性指定的 StatementHandler 类型创建对应的 StatementHandler 实例进行处理，例如 statementType 属性值为 SIMPLE 时，则创建 SimpleStatementHandler 实例。

StatementHandler 对象创建完毕后，接着调用 SimpleExecutor 类的 prepareStatement() 方法创建 JDBC 中的 Statement 对象，然后为 Statement 对象设置参数操作。Statement 对象初始化工作完成后，再调用 StatementHandler 的 query() 方法执行查询操作。SimpleExecutor 类中 prepareStatement() 方法的具体内容，代码如下：

```java
private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
    Statement stmt;
    // 获取JDBC中的Connection对象
    Connection connection = getConnection(statementLog);
    // 调用StatementHandler的prepare()方法创建Statement对象
    stmt = handler.prepare(connection, transaction.getTimeout());
    // 调用StatementHandler对象的parameterize()方法设置参数
    handler.parameterize(stmt);
    return stmt;
  }
```

在 SimpleExecutor 类的 prepareStatement() 方法中，首先获取 JDBC 中的 Connection 对象，然后调用 StatementHandler 对象的 prepare() 方法创建 Statement 对象，接着调用 StatementHandler 对象的 parameterize() 方法（parameterize() 方法中会使用 ParameterHandler 为 Statement 对象设置参数）。

MyBatis 的 StatementHandler 接口有几个不同的实现类，分别为 SimpleStatementHandler、PreparedStatementHandler 和 CallableStatementHandler。MyBatis 默认情况下会使用 PreparedStatementHandler 与数据库交互。PreparedStatementHandler 的 query() 方法的实现，代码如下：

```java
@Override
  public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {
    PreparedStatement ps = (PreparedStatement) statement;
    // 调用PreparedStatement对象的execute()方法，执行SQL语句
    ps.execute();
    // 调用ResultSetHandler的handleResultSets（）方法处理结果集
    return resultSetHandler.<E> handleResultSets(ps);
  }
```

如上面的代码所示，在 PreparedStatementHandler 的 query() 方法中，首先调用 PreparedStatement 对象的 execute() 方法执行 SQL 语句，然后调用 ResultSetHandler 的 handleResultSets() 方法处理结果集。

ResultSetHandler 只有一个默认的实现，即 DefaultResultSetHandler 类，DefaultResultSetHandler 类 handleResultSets() 方法的关键代码：

```java
@Override
  public List<Object> handleResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling results").object(mappedStatement.getId());
    final List<Object> multipleResults = new ArrayList<Object>();
    int resultSetCount = 0;
    // 1、获取ResultSet对象，將ResultSet对象包装为ResultSetWrapper
    ResultSetWrapper rsw = getFirstResultSet(stmt);
    // 2、获取ResultMap信息，一般只有一个ResultMap
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    int resultMapCount = resultMaps.size();
    // 校验ResultMap,如果该ResultMap名称没有配置，则抛出异常
    validateResultMapsCount(rsw, resultMapCount);
    // 如果指定了多个ResultMap，则对每个ResultMap进行处理
    while (rsw != null && resultMapCount > resultSetCount) {
      ResultMap resultMap = resultMaps.get(resultSetCount);
      // 3、调用handleResultSet方法处理结果集
      handleResultSet(rsw, resultMap, multipleResults, null);
      // 获取下一个结果集对象，需要JDBC驱动支持多结果集
      rsw = getNextResultSet(stmt);
      cleanUpAfterHandlingResultSet();
      resultSetCount++;
    }
    // 如果JDBC驱动支持多结果集，可以通过<select>标签resultSets属性指定多个ResultMap
    // 处理<select>标签resultSets属性，该属性一般情况不会指定
    String[] resultSets = mappedStatement.getResultSets();
    if (resultSets != null) {
      while (rsw != null && resultSetCount < resultSets.length) {
        ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
        if (parentMapping != null) {
          String nestedResultMapId = parentMapping.getNestedResultMapId();
          ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
          //调用handleResultSet方法处理结果集
          handleResultSet(rsw, resultMap, null, parentMapping);
        }
        rsw = getNextResultSet(stmt);
        cleanUpAfterHandlingResultSet();
        resultSetCount++;
      }
    }
    // 对multipleResults进行处理，如果只有一个结果集，则返回结果集中的元素，否则返回多个结果集
    return collapseSingleResultList(multipleResults);
  }
```

如上面的代码所示，DefaultResultSetHandler 类的 handleResultSets() 方法具体逻辑如下：

1.  首先从 Statement 对象中获取 ResultSet 对象，然后将 ResultSet 包装为 ResultSetWrapper 对象，通过 ResultSetWrapper 对象能够更方便地获取数据库字段名称以及字段对应的 TypeHandler 信息。
2.  获取 Mapper SQL 配置中通过 resultMap 属性指定的 ResultMap 信息，一条 SQL Mapper 配置一般只对应一个 ResultMap。
3.  调用 handleResultSet() 方法对 ResultSetWrapper 对象进行处理，将结果集转换为 Java 实体对象，然后将生成的实体对象存放在 multipleResults 列表中。
4.  调用 collapseSingleResultList() 方法对 multipleResults 进行处理，如果只有一个结果集，就返回结果集中的元素，否则返回多个结果集。

到此为止，MyBatis 如何通过调用 Mapper 接口定义的方法执行注解或者 XML 文件中配置的 SQL 语句这一整条链路分析完毕。

## 5. 小结

MyBatis 中 Mapper 的配置分为两部分，分别为 Mapper 接口和 Mapper SQL 配置。MyBatis 通过动态代理的方式创建 Mapper 接口的代理对象，MapperProxy 类中定义了 Mapper 方法执行时的拦截逻辑，通过 MapperProxyFactory 创建代理实例，MyBatis 启动时，会将 MapperProxyFactory 注册到 Configuration 对象中。另外，MyBatis 通过 MappedStatement 类描述 Mapper SQL 配置信息，框架启动时，会解析 Mapper SQL 配置，将所有的 MappedStatement 对象注册到 Configuration 对象中。

通过 Mapper 代理对象调用 Mapper 接口中定义的方法时，会执行 MapperProxy 类中的拦截逻辑，将 Mapper 方法的调用转换为调用 SqlSession 提供的 API 方法。在 SqlSession 的 API 方法中通过 Mapper 的 Id 找到对应的 MappedStatement 对象，获取对应的 SQL 信息，通过 StatementHandler 操作 JDBC 的 Statement 对象完成与数据库的交互，然后通过 ResultSetHandler 处理结果集，将结果返回给调用者。

