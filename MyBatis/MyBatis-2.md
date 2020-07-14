# SqlSession 的创建过程

简化流程描述，可将 SqlSession 的创建过程拆解为 3 个阶段：Configuration 实例的创建过程、SqlSessionFactory 实例的创建过程和 SqlSession 实例化的过程。

## 1. XPath 方式解析 XML 文件

MyBatis 的主配置文件和 Mapper 配置都使用的是 XML 格式。MyBatis 中的 Configuration 组件用于描述主配置文件信息，框架在启动时会解析 XML 配置，将配置信息转换为 Configuration 对象。

JDK API 中提供了 3 种方式解析 XML，分别为 DOM、SAX 和 XPath。这 3 种方式都有各自的特点，具体优缺点读者可参考相关资料。在这 3 种方式中，API 最易于使用的就是 XPath 方式，MyBatis 框架中也采用 XPath 方式解析 XML 文件中的配置信息。

如何通过 XPath 方式解析 XML 文件。假设我们有如下 XML 文件：

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<users>
    <user id = "1">
        <name>张三</name>
        <createTime>2018-06-06 00:00:00</createTime>
        <passward>admin</passward>
        <phone>180000000</phone>
        <nickName>阿毛</nickName>
    </user>
    <user id = "2">
        <name>李四</name>
        <createTime>2018-06-06 00:00:00</createTime>
        <passward>admin</passward>
        <phone>180000001</phone>
        <nickName>明明</nickName>
    </user>
</users>

```

XML 文件中的配置信息可以通过一个 Java 类来描述，代码如下：

```java
@Data
public class UserEntity {
    private Long id;
    private String name;
    private Date createTime;
    private String password;
    private String phone;
    private String nickName;
}
```

我们需要将 XML 内容转换为 UserEntity 实体对象，存放在 List 对象中，解析代码如下：

```java
public class XPathExample {

    @Test
    public void testXPathParser() {
        try {
            // 1. 创建DocumentBuilderFactory实例
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 创建DocumentBuilder实例
            DocumentBuilder builder = factory.newDocumentBuilder();
            InputStream inputSource = Resources.getResourceAsStream("users.xml");
            Document doc = builder.parse(inputSource);
            // 2. 获取XPath实例
            XPath xpath = XPathFactory.newInstance().newXPath();
            // 3. 执行XPath表达式，获取节点信息
            NodeList nodeList = (NodeList)xpath.evaluate("/users/*", doc, XPathConstants.NODESET);
            List<UserEntity> userList = new ArrayList<>();
            for(int i=1; i < nodeList.getLength() + 1; i++) {
                String path = "/users/user["+i+"]";
                String id = (String)xpath.evaluate(path + "/@id", doc, XPathConstants.STRING);
                String name = (String)xpath.evaluate(path + "/name", doc, XPathConstants.STRING);
                String createTime = (String)xpath.evaluate(path + "/createTime", doc, XPathConstants.STRING);
                String passward = (String)xpath.evaluate(path + "/passward", doc, XPathConstants.STRING);
                String phone = (String)xpath.evaluate(path + "/phone", doc, XPathConstants.STRING);
                String nickName = (String)xpath.evaluate(path + "/nickName", doc, XPathConstants.STRING);
                // 调用buildUserEntity()方法，构建UserEntity对象
                UserEntity userEntity = buildUserEntity(id,name, createTime, passward, phone, nickName);
                userList.add(userEntity);
            }
            System.out.println(JSON.toJSONString(userList));
        } catch (Exception e) {
            throw new BuilderException("Error creating document instance.  Cause: " + e, e);
        }
    }

    private UserEntity buildUserEntity(String id,String name,
                                       String createTime, String passward,
                                       String phone, String nickName)
            throws IllegalAccessException, InvocationTargetException {
        UserEntity userEntity = new UserEntity();
        DateConverter dateConverter = new DateConverter(null);
        dateConverter.setPattern("yyyy-MM-dd HH:mm:ss");
        ConvertUtils.register(dateConverter,Date.class);
        BeanUtils.setProperty(userEntity,"id",id);
        BeanUtils.setProperty(userEntity,"name",name);
        BeanUtils.setProperty(userEntity,"createTime",createTime);
        BeanUtils.setProperty(userEntity,"passward",passward);
        BeanUtils.setProperty(userEntity,"phone",phone);
        BeanUtils.setProperty(userEntity,"nickName",nickName);
        return userEntity;
    }
}
```

为了简化 XPath 解析操作，MyBatis 通过 XPathParser 工具类封装了对 XML 的解析操作，同时使用 XNode 类增强了对 XML 节点的操作。使用 XNode 对象，我们可以很方便地获取节点的属性、子节点等信息。

使用 XPathParser 工具类将 users.xml 文件中的配置信息转换为 UserEntity 实体对象，具体代码如下：

```java
public class XPathParserExample {

    @Test
    public void testXPathParser() throws Exception {
        Reader resource = Resources.getResourceAsReader("users.xml");
        XPathParser parser = new XPathParser(resource);
        // 注册日期转换器
        DateConverter dateConverter = new DateConverter(null);
        dateConverter.setPattern("yyyy-MM-dd HH:mm:ss");
        ConvertUtils.register(dateConverter, Date.class);
        List<UserEntity> userList = new ArrayList<>();
        // 调用evalNodes（）方法获取XNode列表
        List<XNode> nodes = parser.evalNodes("/users/*");
        // 对XNode对象进行遍历，获取user相关信息
        for (XNode node : nodes) {
            UserEntity userEntity = new UserEntity();
            Long id = node.getLongAttribute("id");
            BeanUtils.setProperty(userEntity, "id", id);
            List<XNode> childNods = node.getChildren();
            for (XNode childNode : childNods) {
                    BeanUtils.setProperty(userEntity, childNode.getName(),
                            childNode.getStringBody());
            }
            userList.add(userEntity);
        }
        System.out.println(JSON.toJSONString(userList));
    }
}
```

如上面的代码所示，使用 MyBatis 封装的 XPathParser 对 XML 进行解析，省去了 Document 对象和 XPath 对象的创建过程，XPathParser 工具类封装了执行 XPath 表达式的方法，很大程度上简化了 XML 解析过程。

## 2. Configuration 实例创建过程

Configuration 是 MyBatis 中比较重要的组件，主要有以下 3 个作用：

1.  用于描述 MyBatis 配置信息，例如 \<settings> 标签配置的参数信息。
2.  作为容器注册 MyBatis 其他组件，例如 TypeHandler、MappedStatement 等。
3.  提供工厂方法，创建 ResultSetHandler、StatementHandler、Executor、ParameterHandler 等组件实例。

在 SqlSession 实例化前，首先解析 MyBatis 主配置文件及所有 Mapper 文件，创建 Configuration 实例。所以在介绍 SqlSession 对象创建过程之前，先来了解一下 Configuration 对象的创建过程。

MyBatis 通过 XMLConfigBuilder 类完成 Configuration 对象的构建工作。下面是通过 XMLConfigBuilder 类创建 Configuration 的案例代码：

```java
    @Test
    public void testConfiguration() throws IOException {
        Reader reader = Resources.getResourceAsReader("mybatis-config.xml");
        // 创建XMLConfigBuilder实例
        XMLConfigBuilder builder = new XMLConfigBuilder(reader);
        // 调用XMLConfigBuilder.parse（）方法，解析XML创建Configuration对象
        Configuration conf = builder.parse();
    }
```

如上面的代码所示，首先以 MyBatis 主配置文件输入流作为参数，创建了一个 XMLConfigBuilder 对象，接着调用 XMLConfigBuilder 对象的 parse() 方法创建 Configuration 对象。XMLConfigBuilder 类 parse() 方法的实现，代码如下：

```java
  public Configuration parse() {
    // 防止parse（）方法被同一个实例多次调用
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    // 调用XPathParser.evalNode（）方法，创建表示configuration节点的XNode对象。
    // 调用parseConfiguration（）方法对XNode进行处理
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }
```

在 XMLConfigBuilder 类的 parse() 方法中，首先调用 XPathParser 对象的 evalNode() 方法获取 XML 配置文件中 \<configuration> 节点对应的 XNode 对象，接着调用 parseConfiguration() 方法通过该 XNode 对象获取更多配置信息。下面是 XMLConfigBuilder 类中 parseConfiguration() 方法的实现：

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

在 parseConfiguration() 方法中，对于 \<configuration> 标签的子节点，都有一个单独的方法处理，例如使用 propertiesElement() 方法解析 \<properties> 标签，使用 pluginElement() 方法解析 \<plugin> 标签。MyBatis 主配置文件中所有标签的用途如下。

-   \<properties>：用于配置属性信息，这些属性的值可以通过 ${…} 方式引用。
-   \<settings>：通过一些属性来控制 MyBatis 运行时的一些行为。例如，指定日志实现、默认的 Executor 类型等。

```xml
	<settings>
		<setting name="useGeneratedKeys" value="true"/>
		<setting name="mapUnderscoreToCamelCase" value="true"/>
		<setting name="logImpl" value="LOG4J"/>
		<setting name="cacheEnabled" value="true"/>
	</settings>
```

-   \<typeAliases>：用于配置类型别名，目的是为 Java 类型设置一个更短的名字。它存在的意义仅在于用来减少类完全限定名的冗余。
-   \<plugins>：用于注册用户自定义的插件信息。
-   \<objectFactory>：MyBatis 通过对象工厂（ObjectFactory）创建参数对象和结果集映射对象，默认的对象工厂需要做的仅仅是实例化目标类，要么通过默认构造方法，要么在参数映射存在的时候通过参数构造方法来实例化。
-   \<objectWrapperFactory>：MyBatis 通过 ObjectWrapperFactory 创建 ObjectWrapper 对象，通过 ObjectWrapper 对象能够很方便地获取对象的属性、方法名等反射信息。
-   \<reflectorFactory>：MyBatis 通过反射工厂（ReflectorFactory）创建描述 Java 类型反射信息的 Reflector 对象，通过 Reflector 对象能够很方便地获取 Class 对象的 Setter/Getter 方法、属性等信息。
-   \<environments>：用于配置 MyBatis 数据连接相关的环境及事务管理器信息。通过该标签可以配置多个环境信息，然后指定具体使用哪个。
-   \<databaseIdProvider>：MyBatis 能够根据不同的数据库厂商执行不同的 SQL 语句，该标签用于配置数据库厂商信息。
-   \<typeHandlers>：用于注册用户自定义的类型处理器（TypeHandler）。
-   \<mappers>：用于配置 MyBatis Mapper 信息。

MyBatis 框架启动后，首先创建 Configuration 对象，然后解析所有配置信息，将解析后的配置信息存放在 Configuration 对象中。

## 3. SqlSession实例创建过程

MyBatis 中的 SqlSession 实例使用工厂模式创建，所以在创建 SqlSession 实例之前需要先创建 SqlSessionFactory 工厂对象，然后调用 SqlSessionFactory 对象的 openSession() 方法，代码如下：

```java
	@Test
    public void testSqlSession() throws IOException {
        // 获取Mybatis配置文件输入流
        Reader reader = Resources.getResourceAsReader("mybatis-config.xml");
        // 通过SqlSessionFactoryBuilder创建SqlSessionFactory实例
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
        // 调用SqlSessionFactory的openSession（）方法，创建SqlSession实例
        SqlSession session = sqlSessionFactory.openSession();
    }
```

上面的代码中，为了创建 SqlSessionFactory 对象，首先创建了一个 SqlSessionFactoryBuilder 对象，然后以 MyBatis 主配置文件输入流作为参数，调用 SqlSessionFactoryBuilder 对象的 build() 方法。下面是 build() 方法的实现：

```java
public SqlSessionFactory build(Reader reader, String environment, Properties properties) {
    try {
      XMLConfigBuilder parser = new XMLConfigBuilder(reader, environment, properties);
      return build(parser.parse());
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error building SqlSession.", e);
    } finally {
      ErrorContext.instance().reset();
      try {
        reader.close();
      } catch (IOException e) {
        // Intentionally ignore. Prefer previous error.
      }
    }
  }
```

在 build() 方法中，首先创建一个 XMLConfigBuilder 对象，然后调用 XMLConfigBuilder 对象的 parse() 方法对主配置文件进行解析，生成 Configuration 对象。以 Configuration 对象作为参数，调用重载的 build() 方法，该方法实现如下：

```java
public SqlSessionFactory build(Configuration config) {
    return new DefaultSqlSessionFactory(config);
  }
```

SqlSessionFactory 接口只有一个默认的实现，即 DefaultSqlSessionFactory。在上面的代码中，重载的 build() 方法中以 Configuration 对象作为参数，通过 new 关键字创建了一个 DefaultSqlSessionFactory 对象。

DefaultSqlSessionFactory 类对 openSession() 方法的实现：

```java
  @Override
  public SqlSession openSession() {
    return openSessionFromDataSource(configuration.getDefaultExecutorType(), null, false);
  }
```

如上面的代码所示，openSession() 方法中直接调用 openSessionFromDataSource() 方法创建 SqlSession 实例。下面是 openSessionFromDataSource() 方法的实现：

```java
private SqlSession openSessionFromDataSource(ExecutorType execType, TransactionIsolationLevel level, boolean autoCommit) {
    Transaction tx = null;
    try {
      // 获取Mybatis主配置文件配置的环境信息
      final Environment environment = configuration.getEnvironment();
      // 创建事务管理器工厂
      final TransactionFactory transactionFactory = getTransactionFactoryFromEnvironment(environment);
      // 创建事务管理器
      tx = transactionFactory.newTransaction(environment.getDataSource(), level, autoCommit);
      // 根据Mybatis主配置文件中指定的Executor类型创建对应的Executor实例
      final Executor executor = configuration.newExecutor(tx, execType);
      // 创建DefaultSqlSession实例
      return new DefaultSqlSession(configuration, executor, autoCommit);
    } catch (Exception e) {
      closeTransaction(tx); // may have fetched a connection so lets call close()
      throw ExceptionFactory.wrapException("Error opening session.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }
```

上面的代码中，首先通过 Configuration 对象获取 MyBatis 主配置文件中通过 \<environment> 标签配置的环境信息，然后根据配置的事务管理器类型创建对应的事务管理器工厂。MyBatis 提供了两种事务管理器，分别为 JdbcTransaction 和 ManagedTransaction。其中，JdbcTransaction 是使用JDBC中的 Connection 对象实现事务管理的，而 ManagedTransaction 表示事务由外部容器管理。这两种事务管理器分别由对应的工厂类 JdbcTransactionFactory 和 ManagedTransactionFactory 创建。

事务管理器对象创建完毕后，接着调用 Configuration 对象的 newExecutor() 方法，根据 MyBatis 主配置文件中指定的 Executor 类型创建对应的 Executor 对象，最后以 Executor 对象和 Configuration 对象作为参数，通过 Java 中的 new 关键字创建一个 DefaultSqlSession 对象。DefaultSqlSession 对象中持有 Executor 对象的引用，真正执行 SQL 操作的是 Executor 对象。

