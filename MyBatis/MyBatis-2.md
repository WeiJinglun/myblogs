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

```

