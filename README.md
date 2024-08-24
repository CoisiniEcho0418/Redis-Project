# RUN

------

> 这是黑马Redis课程项目的后端项目个人实现版
>
> 学习课程链接：https://www.bilibili.com/video/BV1cr4y1671t/

要启动项目请参照/src/main/resources/application-base.yaml文件修改相应的配置后在同目录下生成application.yaml



### 个人优化

#### 1、**批量查询用户信息**（TODO）

 **背景：**在自己测试过程中，发现切换至首页时经常出现请求超时而导致某些数据获取不到的情况（比如获取热门博客数据，要涉及到多次数据库的查询——blog的查询和相关用户信息的查询），因此尝试从以下几个方面优化

要优化这段代码的性能，我们可以从以下几个方面入手：

1. **减少数据库查询次数**：原代码中，对于每篇博客都分别查询用户信息和点赞状态，这样的操作会导致多次数据库查询。可以通过一次性查询批量数据来减少数据库的访问次数。
2. **使用批量处理**：如果 `queryBlogUser(blog)` 和 `checkIsLiked(blog)` 这两个方法内部都是通过单个ID进行查询，那么可以考虑批量查询用户信息和点赞状态，然后将结果映射到相应的博客对象中。
3. **缓存热点数据**：如果某些博客或用户信息是热点数据，可以考虑使用缓存，以减少数据库查询的频率。

##### 优化后的代码示例

```
java复制代码@Override
public Result queryHotBlog(Integer current) {
    // 根据点赞数排序查询
    Page<Blog> page = query().orderByDesc("liked").page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
    
    // 获取当前页数据
    List<Blog> records = page.getRecords();
    if (records.isEmpty()) {
        return Result.ok(Collections.emptyList());
    }
    
    // 提取用户ID和博客ID
    List<Long> userIds = records.stream().map(Blog::getUserId).distinct().collect(Collectors.toList());
    List<Long> blogIds = records.stream().map(Blog::getId).collect(Collectors.toList());

    // 批量查询用户信息
    Map<Long, User> userMap = queryUserByIds(userIds);

    // 批量查询点赞状态
    Map<Long, Boolean> likedMap = checkIsLikedBatch(blogIds);

    // 填充用户信息和点赞状态
    records.forEach(blog -> {
        blog.setUser(userMap.get(blog.getUserId()));
        blog.setLiked(likedMap.getOrDefault(blog.getId(), false));
    });

    return Result.ok(records);
}
```

##### 具体优化点

1. **批量查询用户信息**:
   - `queryUserByIds(userIds)`：假设这是一个可以根据一组用户ID批量查询用户信息的方法，返回一个以用户ID为键的 `Map<Long, User>`。
   - 这样只需要一次数据库查询，就可以获取所有博客相关的用户信息。
2. **批量查询点赞状态**:
   - `checkIsLikedBatch(blogIds)`：假设这是一个可以根据一组博客ID批量查询点赞状态的方法，返回一个以博客ID为键的 `Map<Long, Boolean>`。
   - 这样可以避免对每篇博客进行单独的点赞状态查询。
3. **减少空列表处理**:
   - 在查询后立即检查 `records` 是否为空。如果为空，可以直接返回空结果，避免不必要的处理。
4. **缓存策略**:
   - 如果博客的用户信息或点赞状态是经常被访问的，可以引入缓存策略。例如，可以使用Redis等分布式缓存来缓存热点数据，从而减少对数据库的访问。

通过这些优化，可以显著减少数据库访问次数和提高方法的整体执行效率。

#### 2、登录校验优化

**背景：**登录校验功能有时耗时较大，需要排查原因并优化



#### 3、共同关注列表逻辑优化

要实现一个高性能的“发现共同关注”列表接口，可以通过优化数据库查询、合理使用索引、以及在必要时进行数据缓存来提升性能。以下是可能的实现思路：

##### 1. 优化SQL查询

首先，你需要一个高效的SQL查询来查找两个用户之间的共同关注者。这可以通过一个带有JOIN操作的SQL查询来实现。

假设当前用户的ID是`currentUserId`，目标用户的ID是`id`。查询可以这样写：

```
sql复制代码SELECT f1.follow_user_id 
FROM tb_follow f1 
JOIN tb_follow f2 
ON f1.follow_user_id = f2.follow_user_id 
WHERE f1.user_id = #{currentUserId} 
AND f2.user_id = #{id};
```

这个查询使用了`JOIN`，来获取当前用户和目标用户的共同关注列表。

##### 2. 使用索引

为了提高查询性能，请确保在 `tb_follow` 表上的 `user_id` 和 `follow_user_id` 字段上创建了合适的索引：

```
sql
复制代码
CREATE INDEX idx_user_follow ON tb_follow(user_id, follow_user_id);
```

这个复合索引可以帮助加快查询的速度，特别是在处理大量数据时。

##### 3. Java接口实现

在Java代码中，你可以通过调用Mapper或Repository来执行这个查询，并返回结果。

以下是实现该接口的Java代码示例：

```
java复制代码@Service
public class FollowService {

    @Autowired
    private FollowMapper followMapper;

    public Result findCommonFollows(Long id) {
        Long currentUserId = getCurrentUserId();  // 获取当前用户ID，可以通过Session或其他方式获取
        
        List<Long> commonFollows = followMapper.findCommonFollows(currentUserId, id);
        
        return Result.ok(commonFollows);
    }
}
```

`FollowMapper` 中的对应方法：

```
java复制代码@Mapper
public interface FollowMapper extends BaseMapper<Follow> {

    @Select("SELECT f1.follow_user_id " +
            "FROM tb_follow f1 " +
            "JOIN tb_follow f2 ON f1.follow_user_id = f2.follow_user_id " +
            "WHERE f1.user_id = #{currentUserId} AND f2.user_id = #{id}")
    List<Long> findCommonFollows(@Param("currentUserId") Long currentUserId, @Param("id") Long id);
}
```

##### 4. 缓存策略（可选）

如果发现这个查询接口被频繁调用，并且用户的关注列表不经常变化，可以考虑引入缓存策略。例如，使用Redis缓存用户的关注列表或共同关注列表，从而减少数据库的查询压力。

##### 5. 考虑分页（可选）

如果共同关注的人数较多，可以考虑在接口中引入分页功能，避免一次性查询过多数据。

```
java复制代码public Result findCommonFollows(Long id, int page, int size) {
    Long currentUserId = getCurrentUserId();  
    PageHelper.startPage(page, size);
    
    List<Long> commonFollows = followMapper.findCommonFollows(currentUserId, id);
    
    return Result.ok(commonFollows);
}
```

通过以上的优化思路，能有效地提高接口的查询性能，特别是在数据量较大的场景下。
