import com.guichaguri.trackplayer.service.focus.PendingSlots;

/**
 * PendingSlots 的语义断言（纯 JDK，无需 Android SDK / JUnit）。
 * 运行方式：node scripts/check_pending_slots.js（内部 javac + java）。
 *
 * 这些用例守的是 RT-01/RT-04 真正生效的那一半：
 * 撤销调用点齐全（静态契约）之后，还要保证「撤销真的能让到点的任务失效」。
 */
public final class PendingSlotsTest {

  private static int pass = 0;
  private static int fail = 0;

  public static void main(String[] args) {
    System.out.println("PendingSlots 代次簿记断言");
    try {
      // 1. 未排队时任何 token 都无效
      PendingSlots s = new PendingSlots();
      expect("初始：NONE 无效", !s.isResumeCurrent(PendingSlots.NONE));
      expect("初始：任意正数无效", !s.isResumeCurrent(1L));
      expect("初始：resumeToken 为 NONE", s.resumeToken() == PendingSlots.NONE);

      // 2. 排一次就有效
      long t1 = s.armResume();
      expect("排队后：token 非 NONE", t1 != PendingSlots.NONE);
      expect("排队后：token 有效", s.isResumeCurrent(t1));

      // 3. 二次排队必须让第一次失效（这就是「到点前先问一句」的核心）
      long t2 = s.armResume();
      expect("二次排队：新 token 有效", s.isResumeCurrent(t2));
      expect("二次排队：旧 token 失效", !s.isResumeCurrent(t1));
      expect("二次排队：代次号递增", t2 > t1);

      // 4. disarm 让当前代次失效，并返回给 removeCallbacks
      long disarmed = s.disarmResume();
      expect("disarm：返回的是当前 token", disarmed == t2);
      expect("disarm：之后失效", !s.isResumeCurrent(t2));
      expect("disarm：resumeToken 归零", s.resumeToken() == PendingSlots.NONE);

      // 5. 重复 disarm 不得把已失效的代次「复活」
      expect("重复 disarm：返回 NONE", s.disarmResume() == PendingSlots.NONE);
      expect("重复 disarm：仍然无效", !s.isResumeCurrent(t2));

      // 6. 撤销后重新排队可用
      long t3 = s.armResume();
      expect("撤销后重排：有效", s.isResumeCurrent(t3));
      expect("撤销后重排：旧 token 不会跟着复活", !s.isResumeCurrent(t2));

      // 7. 两种任务互不干扰
      long r1 = s.armRecheck();
      expect("recheck：有效", s.isRecheckCurrent(r1));
      expect("recheck：不影响 resume 代次", s.isResumeCurrent(t3));
      s.disarmResume();
      expect("撤 resume 不影响 recheck", s.isRecheckCurrent(r1));
      long r2 = s.armRecheck();
      expect("recheck 二次排队：旧失效", !s.isRecheckCurrent(r1));
      expect("recheck 二次排队：新有效", s.isRecheckCurrent(r2));

      // 8. reset 全清
      s.reset();
      expect("reset：resume 失效", !s.isResumeCurrent(t3));
      expect("reset：recheck 失效", !s.isRecheckCurrent(r2));
      expect("reset：两者 token 归零", s.resumeToken() == PendingSlots.NONE && s.recheckToken() == PendingSlots.NONE);

      // 9. 跨类型代次号唯一（便于日志里区分第几次排队）
      long a = s.armResume();
      long b = s.armRecheck();
      expect("跨类型：代次号不重复", a != b);
    } catch (Throwable t) {
      fail++;
      System.out.println("  ❌ 用例异常中断：" + t);
    }
    System.out.println("\n结果：" + pass + " 通过 / " + fail + " 失败");
    if (fail > 0) System.exit(1);
  }

  private static void expect(String name, boolean ok) {
    if (ok) {
      pass++;
      System.out.println("  ✅ " + name);
    } else {
      fail++;
      System.out.println("  ❌ " + name);
    }
  }
}
