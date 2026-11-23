package com.jack.mc.seckill.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模拟登录入参。
 * <p>
 * <b>这是测试设施，不是登录</b>：没有任何凭据校验，只要给出 userId 就签发 token。
 * 所以它同时受两道限制：
 * <ol>
 *   <li>整个控制器由 <code>mc.auth.mock-login-enabled</code> 控制，默认关闭；</li>
 *   <li>即便开启，也明确标注「生产环境不得配置此项」。</li>
 * </ol>
 * 第二轮评审指出「当前环境任何人都能成为任意用户」，指的就是这个接口无条件可用。
 * 删掉它会让联调与压测无法进行（拿不到第一个 token），
 * 所以采用「默认关闭 + 显式开关」而不是直接删除。
 */
@Data
public class TokenRequest {

    @NotNull(message = "不能为空")
    @Min(value = 1, message = "必须大于 0")
    private Long userId;

    @Size(max = 32, message = "长度不能超过 32")
    private String nickname;

    /**
     * 想要的角色，只能是 USER 或 ADMIN，缺省 USER。
     * <p>
     * 用白名单正则而不是自由字符串：这个值会被写进 JWT 并参与下游的权限判断，
     * 允许任意值等于给自己留了一个「拼错就绕不过、拼对就能过」的模糊地带。
     * 想测管理接口就显式传 ADMIN，别指望服务端猜。
     */
    @Pattern(regexp = "USER|ADMIN", message = "只能是 USER 或 ADMIN")
    private String role = "USER";
}
