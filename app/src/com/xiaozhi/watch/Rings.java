package com.xiaozhi.watch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import android.util.Base64;

/**
 * 情绪球几何数据 —— 由 tools/gen_eb_java.js 从 NX_emotion-ball/js/rings.js 自动生成。
 * <b>请勿手改</b>，改了下次生成会丢。
 *
 * <p>坐标系：viewBox = -15 -15 259 259（259x259 的正方形设计画布），
 * 头部中心 HEAD_C。绘制时整体缩放到 View 尺寸即可，不用关心这里的具体数值。</p>
 *
 * <p>数据以 float32 小端 + Base64 存放，运行时解码。原因见 gen_eb_java.js 顶部注释
 * （直接写字面量会撑爆 &lt;clinit&gt; 的 64KB 字节码上限）。</p>
 */
public final class Rings {

    /** 设计画布边长（viewBox 宽高） */
    public static final float VIEW = 259f;
    /** viewBox 原点偏移（左上角的 x/y） */
    public static final float VIEW_OFF = -15f;
    /** 头部中心在设计画布里的坐标 */
    public static final float HEAD_C = 114.2705f;
    /** 双眼间距的一半 */
    public static final float EYE_HALF = 21f;
    /** 庆祝撒花的金色 */
    public static final String STAR_GOLD = "#f4c34e";

    /** 25 组表情眼环，每组 = [左眼环, 右眼环]，各 48 点。索引含义见 Emotions 的 pool 字段。 */
    public static final int EXPRESSION_COUNT = 25;
    /** 每只眼的轮廓点数 */
    public static final int RING_POINTS = 48;

    // ---------------- 眼环数据（每组 2 眼 x 48 点 x (x,y)）----------------
    private static final String RING_00 =
            "KVwCQ4XrN0LDtQRDj8I4QuH6BkNxPTtCKRwJQ+xRP0JS+ApDuB5FQlJ4DEMpXExCFK4NQ+F6VELhug5DhetcQo/CD0OkcGVCrscQ"
            +             "Q8P1bUI9yhFD4Xp2Qj3KEkM9Cn9Cj8ITQ+zRg0LDtRRD1yOIQkihFUMAgIxCH4UWQynckEK4XhdDj0KVQnH9F0Ps0ZlCmhkYQx+F"
            +             "nkLXoxdD1yOjQgqXFkMpXKdCPQoVQ2bmqkKaGRNDXI+tQmbmEENxPa9CXI8OQyncr0JSOAxDhWuvQuH6CUPh+q1CFO4HQ9ejq0KF"
            +             "KwZD4XqoQq7HBEMUrqRCpLADQwCAoEIfxQJD9iicQrjeAUOux5dCpPAAQ6Rwk0IAAABDuB6PQlwP/kLNzIpCmhn8Qh+FhkKaGfpC"
            +             "cT2CQlwP+ELD9XtCKdz1QpqZc0JxvfNC9ihrQlwP8kIpXGJCpHDxQj0KWUIzM/JCUrhPQs1M9ELsUUdCAID3QmZmQEIfhftCXI87"
            +             "Qo8CAEOPwjhCKZwwQ+xRFEJSuDJDKVwWQjOzNEOF6xlCH4U2Q5qZHkIzMzhDuB4kQsO1OUMpXCpCrgc7Q3E9MUIzMzxDXI84Qo9C"
            +             "PUO4HkBCH0U+QwrXR0KPQj9DXI9PQuE6QEMpXFdC9ihBQzMzX0LNDEJDuB5nQoXrQkN7FG9Ccb1DQ7ged0Kuh0RDMzN/Qh9FRUMU"
            +             "roNCUvhFQ83Mh0JSeEZDHwWMQkihRkMpXJBCZmZGQ/aolEJ7lEVD9qiYQjPzQ0OkcJtCe9RBQ/YonELDtT9DzUybQo/CPUNIYZlC"
            +             "rgc8Q4/ClkKuhzpD16OTQq5HOUOaGZBCAEA4Q49CjEJmZjdDrkeIQkihNkNSOIRCuN41Q9cjgELsETVDMzN4QgBANEP2KHBC12Mz"
            +             "QzMzaEJxfTJDrkdgQuyRMUNmZlhCKZwwQ5qZUEK4ni9DSOFIQgqXLkNxPUFCroctQ9ejOUI9iixDhesxQincK0MUrilCj8IrQz0K"
            +             "IUK4nixDMzMZQjNzLkOPwhRC";

    private static final String RING_01 =
            "rkesQnuU+ULsUbJCCtf5QrgeuEIfhftCKVy9Qj2K/kJSuMFCKVwBQ8P1xEKF6wNDHwXHQo/CBkNxPchC4boJQ7geyUJSuAxDHwXK"
            +             "QlK4D0M9CstCw7USQ7gezEKksBVDcT3NQoWrGENmZs5C16MbQ7iez0IpnB5DSOHQQuyRIUNSONJCH4UkQ7ie00JSeCdD4frUQoVr"
            +             "KkOkcNVCpHAtQx8F1EK4XjBDcb3QQmbmMkNcD8xCXM80Qx+FxkJx/TVDPYrAQsN1NkMAgLpCAEA2QxSutEIUbjVDzUyvQj0KNEPX"
            +             "o6pCKRwyQx8Fp0Izsy9Dcb2kQmbmLEPsUaNCw/UpQ8P1oUIAACdD16OgQs0MJENIYZ9CexQhQ/YonkK4Hh5DAACdQtcjG0NI4ZtC"
            +             "9igYQ83MmkIULhVDrseZQjMzEkPNzJhCwzUPQyncl0LDNQxDuB6XQsM1CUNmZpdCpDAGQzMzmULNTANDpHCcQo/CAEOk8KBC4Xr9"
            +             "QilcpkLNzPpC4ToTQ3G94UJxPRZDj0LiQuwRGUNIYeRCrocbQ0jh50LDdR1DH4XsQuG6HkMfBfJCrocfQync90LDNSBDj8L9Qkjh"
            +             "IEMK1wFDH4UhQ83MBENmJiJDj8IHQ3G9IkPhugpD7FEjQ8O1DUO43iNDpLAQQ9djJEOFqxNDZuYkQ/aoFkPXYyVDZqYZQ83MJUNm"
            +             "phxDj8IlQ4WrH0Ph+iRDCpciQ1yPI0OPQiVDKZwhQ3uUJ0PhOh9DM3MpQx+FHEMfxSpD7JEZQ8N1K0PNjBZDZmYrQ4WrE0MfhSpD"
            +             "pDARQz3KKEMzcw9De1QmQ+yRDkOkcCNDexQOQzNzIEMpnA1Dw3UdQykcDUNSeBpDCpcMQ+F6F0M9CgxDAIAUQ1J4C0OuhxFDuN4K"
            +             "Q1yPDkMAQApDCpcLQ5qZCUNIoQhDpPAIQxSuBUMAQAhDUrgCQwqXB0M9iv9CUngHQwCA+ULsEQhDXI/zQgpXCUN7FO5CpDALQ81M"
            +             "6UKuhw1D4XrlQo9CEENI4eJC";

    private static final String RING_02 =
            "FK7RQvYo0EKux9pCrsfRQo9C40LDddVC16PqQgAA20IAgPBC9ijiQqRw9EIAgOpCzUz2Qj2K80IK1/VCrsf8QlK480LX4wJDUjjx"
            +             "QppZB0Nxve5CXM8LQ81M7EIfRRBDSOHpQnG9FEPheudCwzUZQ7ge5UKksB1DrsfiQoUrIkPheuBCZqYmQ49C3kJmJitDexTcQmam"
            +             "L0Oux9lCSCE0Q8P11UJ7VDhDmpnPQoWrO0MAgMdCuN49QwCAvkIU7j5Dj0K1QhTuPkNSOKxCUvg9Q1K4o0L2KDxDmhmcQj2KOUOu"
            +             "x5VCFC42Q3E9kUL2KDJDexSPQoWrLUNI4Y9CexQpQx8FkkLskSRDj0KUQnsUIEMfhZZCCpcbQwrXmEIpHBdDFC6bQkihEkM9ip1C"
            +             "ZiYOQ6Twn0IUrglDKVyiQlI4BUPs0aRCAMAAQ81Mp0J7lPhCcb2pQvao70L2qKxCKdzmQgpXsUKF695Czcy3Qs1M2EL2qL9CpHDT"
            +             "QsN1yEIzs9BCj0IuQ5oZ50IKlzJDexTpQrheNkNSuO1CXE85Q2Zm9ELhOjtDhWv8QrgePEPskQJDXA88Qx8FB0MfhTtDpHALQync"
            +             "OkOa2Q9DKRw6QwBAFEMAQDlDuJ4YQ81MOEPh+hxDcT03Q1xPIUPsETZDKZwlQz3KNENI4SlD12MzQ7geLkPX4zFDXE8yQx9FMENS"
            +             "eDZDUnguQ82MOkOFKyxDmlk+Q8M1KUMUrkFD4bolQ8N1REMK1yFD16NGQ/aoHUP2KEhDH0UZQ/boSEN71BRDpLBIQ2amEEOkMEdD"
            +             "UngNQ9cjREMULgxD9ug/Q+zRDEOuhztDw3UOQ0hhN0P2KBBDj0IzQ+G6EUMKFy9DFC4TQ7jeKkOPghRDuJ4mQ+G6FUN7VCJDCtcW"
            +             "Q48CHkMK1xdDhasZQ3G9GEPNTBVDPYoZQ/boEENxPRpDj4IMQ5rZGkOaGQhDe5QbQzOzA0MU7hxDhev+QmYmH0MzM/dCuB4iQ3uU"
            +             "8ELhuiVDKVzrQgrXKUMAAOhC";

    private static final String RING_03 =
            "SOEyQjOzzkLNzD9CKdzPQvYoTEKaGdJCUrhXQnE91ULsUWJC1yPZQkjha0L2qN1CKVx0QjOz4kJSuHtC1yPoQgAAgUKk8O1Ce5SD"
            +             "QuH680KamYVCj0L6Qj0Kh0IKVwBDSOGHQpqZA0P2KIhC1+MGQ83Mh0KFKwpDzcyGQoVrDUO4HoVCmpkQQ/aogkL2qBNDCtd+Qh+F"
            +             "FkOPwnZCKRwZQzMzbUIpXBtD9ihiQvYoHUM9ClZChWseQ/YoSULsER9DAAA8QgoXH0M9Ci9CAIAeQxSuIkLXYx1DuB4XQuzRG0Mf"
            +             "hQxCuN4ZQwAAA0KamRdDPQr1QQoXFUNxPeZBKVwSQxSu2UFSeA9DKVzPQaRwDEMpXMdBXE8JQ5qZwUGaGQZD9ii+QQrXAkO4Hr1B"
            +             "mhn/QuF6vkE9ivhC4XrCQR8F8kKuR8lBFK7rQj0K00Fcj+VCAADgQQrX30L2KPBB9qjaQhSuAUL2KNZCFK4MQlyP0kIK1xhCPQrQ"
            +             "QlK4JUJxvc5CcT3lQq5Hp0K4nuxCj8KnQkjh80IzM6lCSOH6Qj2Kq0IfxQBDj8KuQincA0PNzLJChasGQ7iet0JIIQlDuB69QjMz"
            +             "C0NxPcNCmtkMQyncyULNDA5DCtfQQj3KDkOaGdhCzQwPQ8N130J71A5DCtfmQkghDkOaGe5CM/MMQ5oZ9UIKVwtDj8L7Qs1MCUPD"
            +             "9QBDSOEGQwDAA0MpHARDMzMGQ80MAUMfRQhDAID7QhTuCUMAgPRCSCELQ49C7UIp3AtDSOHlQpoZDEMAgN5CKdwLQ49C10LXIwtD"
            +             "cT3QQlL4CUOamclCKVwIQ2Zmw0IKVwZDzcy9QhTuA0NI4bhCFC4BQ1K4tEJxPfxCpHCxQrie9UIfBa9CuJ7uQlyPrUJIYedCPQqt"
            +             "Qh8F4ELheq1C16PYQkjhrkJIYdFCUjixQilcykLDdbRCUrjDQj2KuEJcj71CSGG9QuH6t0Jm5sJCexSzQh8FyUKk8K5C16PPQrie"
            +             "q0K4ntZCUjipQkjh3UKPwqdC";

    private static final String RING_04 =
            "SOG0QgCAxkJSOLpCH4XGQuF6v0J7lMdCcb3EQpqZyEIAAMpC16PJQq5Hz0L2qMpCPYrUQjOzy0LNzNlCcb3MQlwP30Kux81C7FHk"
            +             "QuzRzkJ7lOlCSOHPQgrX7kKk8NBCuB70QgAA0kJIYflCXA/TQtej/kK4HtRCpPABQzMz1ULskQRDrkfWQjMzB0MpXNdCZqYJQ8N1"
            +             "2UIAgAtDzUzdQqRwDEOuR+JCKVwMQ7ie50KaWQtDXI/sQuF6CUMpXPBCAAAHQ65H8kLsUQRDUjjyQjOzAUO4HvFC9ij+QgAA8EJm"
            +             "5vhChevuQtej80Ip3O1CSGHuQs3M7EK4HulCcb3rQinc40Izs+pCe5TeQvao6ULsUdlC16PoQj0K1ELXo+dCrsfOQrie5kIfhclC"
            +             "e5TlQnE9xEJcj+RC4fq+QgCA40JSuLlCpHDiQsN1tEIpXOFCPYqvQuxR30Ip3KtC4XrbQh8FqkLhetZCuB6qQtcj0ULXI6xCMzPM"
            +             "QoXrr0KkcMhCmpkcQ7ge4UL26B5D7FHhQhQuIUPNTOJCM3MjQ65H40JSuCVDj0LkQnH9J0NxPeVCj0IqQ3E95kKuhyxDcT3nQs3M"
            +             "LkNxPehC7BExQ3E96UIKVzNDj0LqQpqZNUOuR+tCuN43Q81M7ELXIzpDClftQmZmPEMpXO5Chas+Q2Zm70Kk8EBDZmbwQrgeQ0OF"
            +             "6/FCM7NEQ49C9UIzc0VD16P5Qq6HRUOuR/5C7BFFQ/ZoAUN7FERDj4IDQ3F9QkMULgVDe1RAQ1L4BUM9Cj5DFK4FQz3KO0MKFwVD"
            +             "H4U5Q+yRBEOPQjdDzQwEQ3H9NEM9igNDUrgyQ64HA0MzczBDrocCQxQuLkOuBwJD9ugrQz2KAUPXoylDzQwBQ7heJ0NcjwBDmhkl"
            +             "Q+wRAEN71CJD9ij/QlyPIEP2KP5CPUoeQ9cj/UIfBRxDmhn8QmbmGUOuR/pCj0IYQx8F90LsURdDzczyQnsUF0MzM+5CrocXQ9ej"
            +             "6UJmphhDmpnlQvZoGkO4nuJC";

    private static final String RING_05 =
            "SOGfQqRwAkP2KKZC7JECQ1I4rEL2aANDrsexQtfjBEM9irZCpPAGQ81MukLDdQlDrse8QppZDENSOL5ChWsPQ81Mv0IfhRJDZmbA"
            +             "QrieFUNcj8FCUrgYQ67HwkJczxtDexTEQtfjHkOkcMVCUvghQyncxkI9CiVDKVzIQpoZKEOk8MlCZiYrQ7iey0IULi5DrkfNQlI4"
            +             "MUMfBc5CClc0QzOzzEJmZjdD7FHJQj0KOkNIYcRC4fo7Q1yPvkL2KD1DCle4QpqZPUNcD7JCuF49Q+H6q0JcjzxD7FGmQjMzO0OP"
            +             "QqFCe1Q5Q5oZnUJS+DZDmhmaQsM1NEMzM5hCwzUxQ1yPlkKFKy5DAACVQikcK0PhepNCXA8oQz0KkkJx/SRD9qiQQoXrIUMpXI9C"
            +             "e9QeQ7gejkIAwBtDpPCMQmamGEPs0YtCzYwVQ3G9ikIzcxJDpPCJQuxRD0NxPYpCFC4MQ3sUjEIULglDZmaPQo+CBkNcD5RC9mgE"
            +             "Q1K4mUI9CgNDM/MoQ65HCENcTytDUvgIQ3H9LEOPwgpD9qgtQ0ghDUMpXC1De5QPQ3E9LEM9yhFDM3MqQ3F9E0NSOChDe5QUQ9fj"
            +             "JUMUbhVD7JEjQ1xPFkNxPSFDFC4XQ2bmHkMfBRhDXI8cQ5rZGEPDNRpDZqYZQ5rZF0OkcBpD4XoVQzMzG0MpHBNDpPAbQ+G6EENm"
            +             "phxDClcOQwpXHUMz8wtDjwIeQ82MCUP2qB5D1yMHQx9FH0PhugRDKdwfQ1xPAkMUbiBDFK7/Qj2KIENcD/tCpLAfQz2K90LD9R1D"
            +             "rsf1QvaoG0MULvZCwzUZQ1K4+EIKFxdDFK78QimcFUOksABDe9QUQ5oZA0NxPRRDj4IFQ7ieE0P26AdD4foSQ1xPCkPsURJDM7MM"
            +             "Q2amEUN7FA9DM/MQQ8N1EUPhOhBDe9QTQ3F9D0MzMxZD4boOQ1yPGEMz8w1D9ugaQ2YmDUMAQB1D7FEMQwqXH0PhegtDheshQymc"
            +             "CkNxPSRDUrgJQz2KJkOuxwhD";

    private static final String RING_06 =
            "e5TvQjOz0kJI4fNCCtfSQlwP+EJxvdNCAAD8QqRw1UI9iv9CKdzXQh9FAUOk8NpCM3MCQx+F3kI9SgNDw3XiQuG6A0P2qOZCPcoD"
            +             "Q6Tw6kKuhwNDMzPvQikcA0OFa/NC9qgCQ7ie90IfRQJDCtf7QkjhAUOuBwBDcX0BQ9cjAkPsEQFDAEAEQ7ieAEOaWQZDhev/Qtdj"
            +             "CEOF6/1CPUoKQ3E9+0LD9QtD4fr3QilcDUOPQvRCFG4OQ1I48EJmJg9DAADsQgCAD0Mzs+dCM3MPQwCA40IAAA9De5TfQmYmDkNc"
            +             "D9xCFO4MQ3sU2UJIYQtDUrjWQgqXCUNcD9VCKZwHQzMz1EKPggVD1yPUQrheA0Mzs9RCcT0BQz2K1UKuR/5CpHDWQnsU+kKuR9dC"
            +             "Kdz1QpoZ2ELXo/FCZubYQoVr7UIUrtlCMzPpQgCA2kLh+uRCUrjbQkjh4EL2qN1CPQrdQuxR4EIUrtlCXI/jQkjh1kLNTOdCj8LU"
            +             "QgpX60IpXNNC4bouQzMz5ELDtTBDFC7lQgCAMkOaGedCPQo0Q4/C6UIfRTVDAADtQoUrNkP2qPBCw7U2Q3uU9EJm5jZD16P4QuzR"
            +             "NkNSuPxCXI82Q0hhAEPhOjZD12MCQ9fjNUNmZgRDroc1Q/ZoBkP2KDVD9mgIQwDANEP2aApDzUw0Q2ZmDEPs0TNDSGEOQxQuM0Nc"
            +             "TxBDrkcyQ2YmEkPXIzFDe9QTQ3G9L0NcTxVDKRwuQ66HFkM9SixDpHAXQ+xRKkNS+BdDPUooQ+wRGEM9SiZDpLAXQzNzJEN71BZD"
            +             "uN4iQz2KFUP2qCFD9ugTQ3vUIENcDxJDSGEgQ+wREEM9SiBDPQoOQ66HIEOPAgxD4fogQx8FCkMUbiFDrgcIQwrXIUM9CgZDcT0i"
            +             "Qz0KBEO4niJDrgcCQxTuIkMfBQBDcT0jQwAA/EKamSNDAAD4QikcJENcD/RC9ugkQ81M8EJx/SVDKdzsQppZJ0Ps0elCpPAoQ65H"
            +             "50JxvSpDSGHlQjOzLEPNTORC";

    private static final String RING_07 =
            "pHDTQvYokULXo9lCXI+RQgCA30LXo5NCPYrkQq5Hl0Kux+hCCtebQgrX7EJ7lKBCZubwQuxRpUKk8PRCXA+qQsP1+ELs0a5C4fr8"
            +             "QpqZs0LhegBDZma4QlJ4AkMzM71CM3MEQx8FwkIUbgZDCtfGQmZmCEP2qMtCuF4KQwCA0ELNTAxDZmbVQprZDUOux9pCZqYOQ83M"
            +             "4EJIoQ5DHwXnQs3MDUMAAO1CwzUMQwpX8kIz8wlDuJ72QoUrB0NmZvlCKRwEQx+F+kIfBQFDw/X5QnE9/EJSuPdCClf3Qkjh80JS"
            +             "OPNCMzPvQuxR70IKV+pCSGHrQgCA5ULDdedC9qjgQgCA40IK19tCPYrfQj0K10I9ittCcT3SQj2K10LDdc1CH4XTQhSuyEIAgM9C"
            +             "hevDQqRwy0IULr9CKVzHQsN1ukKPQsNCrse1QuzRv0KambBCZua9QhSuqkIK171C4XqkQhSuv0I9ip5CuB7DQilcmULNzMdCj0KV"
            +             "QgpXzUKkcJJC4bowQwAAf0IfhTNDKdyAQh8FNkMzs4NCZiY4Q1yPh0Ka2TlDMzOMQnsUO0NmZpFCj8I7QwAAl0KPwjtDcb2cQuE6"
            +             "O0NmZqJCe5Q6Qx8FqELX4zlDmpmtQvYoOUMULrNC12M4Q1K4uEIKlzdDj0K+Qo/CNkOux8NCSOE1Q65HyULh+jRDcb3OQh8FNEMU"
            +             "LtRCrgczQ3uU2UJx/TFDw/XeQmbmMEPNTORCAIAvQ+xR6UKPgi1DpHDtQgAAK0NSOPBCFC4oQ+xR8UIKVyVDe5TwQgDAIkN7FO5C"
            +             "uJ4gQ3E96kIfBR9Dw3XlQgAAHkN7FOBCSKEdQ0hh2kLh+h1DFK7UQnvUHkMULs9CKdwfQ83MyUK43iBDZmbEQprZIUPh+r5CPcoi"
            +             "Qx+FuUIzsyNDXA+0QuyRJEN7lK5C9mglQ1wPqULDNSZDH4WjQuH6JkPh+p1CM7MnQ2ZmmEJIYShDzcySQgAAKUP2KI1CPcopQ9ej"
            +             "h0L2aCtDheuCQkjhLUNcD4BC";

    private static final String RING_08 =
            "cT2AQincpkJIYYVC9iioQs3MiUJcD6tC1yONQjMzr0K4Ho9Cmhm0QlK4j0JIYblCXA+PQtejvkKaGY1CXI/DQrgeikLh+sdCUriG"
            +             "QlwPzEKuR4NCuB7QQs3Mf0JSONRCuB55Qilc2EIfhXJCPYrcQgAAbEKPwuBC16NlQgAA5UIpXF9CzUzpQvYoWULXo+1CuB5TQgAA"
            +             "8kJxPU1CpHD2QqRwR0Jm5vpCFK5BQkhh/0IUrjpCFK4BQ6RwMULD9QJDhesmQlI4A0NI4RxCSGECQ9ejFEJSuABDXI8OQpoZ/UKa"
            +             "mQpC9ij4Qs3MCEKF6/JC9igJQrie7UKPwgtC4XroQuF6EEJSuONC9igWQlI430L2KBxC7NHaQvYoIkKFa9ZCcT0oQlwP0kKkcC5C"
            +             "cb3NQlK4NELDdclC9ig7QjMzxULXo0FCAADBQq5HSEIK17xCw/VOQjOzuELNzFVCuJ60QhSuXEJcj7BCj8JjQpqZrEL2KGxCClep"
            +             "QoXrdULNTKdCw3XQQhQuukKk8NVCPYq7Qs3M2kLher5C16PeQriewkIULuFCM7PHQs3M4kLXI81Cj0LkQtej0kJxveVCuB7YQnE9"
            +             "50Kamd1Ccb3oQnsU40KuR+pCXI/oQs3M60I9Cu5CClftQgCA80Jm5u5C4fr4QsN18EKkcP5CXA/yQjPzAUP2qPNCFK4EQzMz9UL2"
            +             "aAdDw/X1QlI4CkPXI/VCHwUNQxSu8kLskQ9DSOHuQoWrEUOaGepCpDATQxSu5EI9ChRDHwXfQtcjFEM9itlCM3MTQ/ao1EKPAhJD"
            +             "j8LQQsP1D0PXI85CpHANQ4VrzELhugpDKdzKQgAACEPNTMlCH0UFQ3G9x0KuhwJDFC7GQpqZ/0LXo8RCuB76QpoZw0L2qPRCe5TB"
            +             "QhQu70J7FMBCM7PpQlyPvkJSOORCXA+9QnG93kJ7lLtCcT3ZQpoZukKPwtNCw3W5QtcjzkIpXLpCPYrIQgrXvEKkcMNC16PAQnE9"
            +             "v0JmZsVC9ii8Qs3MykKFa7pC";

    private static final String RING_09 =
            "rkfYQmbmB0OPQt5CXA8IQ/Yo5EJcjwhDZubpQtdjCUOFa+9CzYwKQ7ie9EKuBwxDhWv5QlzPDUNSuP1C1+MPQ+G6AEPhOhJDH0UC"
            +             "Q83MFEOkcANDzYwXQ1I4BEOkcBpDXI8EQ/ZoHUNSeARDZmYgQxTuA0MKVyNDw/UCQ4UrJkOamQFDe9QoQ3G9/0KuRytD9qj7QsN1"
            +             "LUMfBfdCmlkvQ4Xr8UL26DBDw3XsQkghMkNSuOZCAAAzQwrX4EIfhTNDKdzaQjOzM0NI4dRCPYozQ+H6zkI9CjNDcT3JQsM1MkNS"
            +             "uMNCXA8xQx+FvkJ7lC9DUri5Qj3KLUOFa7VCw7UrQxSusUK4XilDmpmuQlzPJkOPQqxCXA8kQzOzqkKFKyFDHwWqQjMzHkNSOKpC"
            +             "wzUbQ65Hq0KPQhhDUjitQhRuFUOk8K9CH8USQ2Zms0J7VBBD4Xq3QtcjDkO4HrxCAEAMQ1I4wUKksApDFK7GQlJ4CUOFa8xCmpkI"
            +             "Q81M0kJ7FAhDhasvQ1zPAEP2KDFDmtkAQ7ieMkPXIwFDjwI0Q4WrAUOuRzVDpHACQ/ZoNkOFawNDKVw3Q+yRBEO4HjhDmtkFQxSu"
            +             "OEPhOgdDzQw5QxSuCENxPTlDZiYKQx9FOUPXowtDSCE5Q0ghDUOa2ThDCpcOQ4VrOEMfBRBDmtk3Q2ZmEUNmJjdDUrgSQ3tUNkPD"
            +             "9RNDSGE1QykcFUPsUTRD9igWQ2YmM0N7FBdDSOExQ7jeF0OuhzBDcX0YQ5oZL0P26BhDuJ4tQykcGUNIISxD7BEZQ4WrKkOuxxhD"
            +             "rkcpQ3E9GEOPAihDUngXQ0jhJkNxfRZDFO4lQ5pZFUMULiVDXA8UQymcJEMUrhJDcT0kQ3E9EUPNDCRDj8IPQx8FJEMfRQ5D9igk"
            +             "Q67HDEOkcCRD7FELQ7jeJEPX4wlDpHAlQ4+CCEPXIyZDMzMHQ8P1JkMz8wVD9ugnQ83MBENS+ChDAMADQ9cjKkN71AJDZmYrQz0K"
            +             "AkOPwixDFG4BQ6QwLkOPAgFD";

    private static final String RING_10 =
            "rsfOQgCAnEK4HtVCj8KcQhQu20Jcj55Ce5TgQkjhoUIp3ORCXI+mQlK450IzM6xCPQrpQmZmskLXo+hCcb24QnE950KF675CUrjl"
            +             "QpoZxUIzM+RCj0LLQlK44kKkcNFCj0LhQrie10Ls0d9C7NHdQmZm3kIfBeRCHwXdQjMz6kLXo9tChWvwQuxR2kK4nvZCPQrZQgrX"
            +             "/EIUrtdCrocBQ5qZ1UIfhQRDexTSQvYoB0PsUc1CAEAJQ7iex0JIoQpDZmbBQjMzC0N7FLtCw/UKQ1wPtUIz8wlDcb2vQnE9CEOF"
            +             "a6tChesFQ4VrqEJIIQNDw/WmQs0MAEMAAKdCj8L5QgAAqELhevNCZmapQq5H7ULNzKpCexTnQvYorEJI4eBCPYqtQhSu2kLD9a5C"
            +             "4XrUQkhhsELNTM5CCtexQpoZyELsUbNChevBQuzRtEKPwrtCKVy2QpqZtUJm5rdChWuvQjMzukIfhalCZua9QkhhpELNzMJCSGGg"
            +             "QlyPyEJSuJ1Ccf0hQ9cjt0LsESVDAAC4QmbmJ0Ncj7pC7FEqQ8N1vkJSOCxDSGHDQnF9LUNcD8lCzQwuQxQuz0IU7i1DZmbVQjNz"
            +             "LUNcj9tCZuYsQzOz4UJ7VCxD7NHnQsO1K0Ok8O1CXA8rQz0K9EJIYSpDuB76QvaoKUMKFwBD9ugoQ7geA0NIIShD1yMGQ3tUJ0Nm"
            +             "JglDAIAmQ/YoDEN7lCVD1yMPQ3tUJEPh+hFDAIAiQwCAFENmJiBDrocWQ0hhHUMz8xdDClcaQymcGENxPRdDFG4YQ+xRFENIYRdD"
            +             "e9QRQx+FFUNx/Q9DjwITQzPzDkN7FBBD4boOQ+H6DEMAQA9D9ugJQwoXEEP26AZDpPAQQ/boA0OPwhFDZuYAQz2KEkOux/tCPUoT"
            +             "Q1K49UKPAhRD9qjvQqSwFEN7lOlCClcVQ+F640Iz8xVDKVzdQj2KFkNxPddCChcXQ5oZ0UKamRdDpPDKQqRwGEPD9cRCAAAaQ3uU"
            +             "v0LDNRxDMzO7QqTwHkNxPbhC";

    private static final String RING_11 =
            "hes8QpoZtkKkcE5CpPC4Qj0KXUJ7lL5CCtdnQlwPxkLNzG5CuJ7OQnE9ckIzs9dCuB51QgrX4EKkcHhCpPDpQnE9fEIfBfNCUjiA"
            +             "Qh8F/EJcj4JCcX0CQ7gehUKk8AZDZuaHQppZC0NI4YpC4boPQ5oZjkLsERRDPYqRQrheGENxPZVCKZwcQz0KmUJ71CBDuB6cQqQw"
            +             "JUM9Cp1CrscpQ3E9m0LNTC5DZmaWQlI4MkNI4Y5CKdw0Q4XrhUL26DVDpHB5Qs2MNUPD9WdCXA80Q7geWEIUrjFDKVxKQnuULkN7"
            +             "FD9ChesqQ+F6NkJ71CZDw/UuQpqZIkMK1ydCe1QeQ/YoIUKPAhpDSOEaQvaoFUN7FBVCj0IRQ1K4D0J71AxDj8IKQrheCENxPQZC"
            +             "SOEDQzMzAkJxvf5CMzP9Qfao9ULNzPZBPYrsQpqZ8UFIYeNC16PwQdcj2kLNzPZBHwXRQsP1AUJIYchCPQoMQtejwEKPwhlC4Xq6"
            +             "QlyPKkIzs7ZCKdzZQo/CkkIfheNCMzOTQo/C7EIfBZZC4fr0Qrgem0LXo/tC1yOiQpoZAEMUrqpCZqYBQyncs0I9CgND9ii9QhRu"
            +             "BEOkcMZC7NEFQ3G9z0JSOAdDHwXZQpqZCEPsUeJCcf0JQ7ie60IpXAtDhev0QuG6DENSOP5CSCEOQ4/CA0OPgg9D9mgIQ+zREEN7"
            +             "FA1Dj4IRQyncEUP26BBD9qgWQwoXD0PXIxtDhSsMQ3H9HkNIYQhDjwIiQwAABEPNDCRDH4X+Qq4HJUMK1/RCKdwkQ3uU60LheiND"
            +             "ClfjQjPzIENSuNxCFG4dQwpX2EIpHBlDpHDVQnF9FENSuNJCe9QPQ+H6z0IULgtDcT3NQq6HBkPhespCSOEBQ3G9x0LDdfpCw/XE"
            +             "QvYo8UIULsJCKdznQmZmv0J7lN5Ce5S8Qq5H1UKux7lCAADMQuH6tkJSuMJCuJ61QtcjuUKPwrZCPYqvQq5HukIfhaZCPQrAQnG9"
            +             "nkJ7lMdCFK6YQilc0ELXo5RC";

    private static final String RING_12 =
            "UrjNQlyPAUOux9RCw/UBQzOz20KPwgJDhWviQqTwA0PNzOhCAIAFQ67H7kKFawdDj0L0QqSwCUO4HvlCrkcMQ65H/UL2KA9De1QA"
            +             "Qz1KEkMKlwFDKZwVQ7heAkN7FBlD9qgCQ7ieHEMUbgJD9iggQxSuAUNIoSNDhWsAQ8P1JkNmZv1C7BEqQ3sU+ULX4yxDPQr0Qtdj"
            +             "L0MpXO5CH4UxQ9cj6EIAQDNDH4XhQj2KNEO4ntpCZmY1Q1yP00Ls0TVDw3XMQnvUNUNmZsVChWs1Q+F6vkK4njRDj8K3QqRwM0NI"
            +             "YbFCSOExQ2Zmq0LD9S9DheulQqSwLUNcD6FCmhkrQ2bmnEJSOChDH4WZQpoZJUMAAJdCH8UhQ6RwlUJcTx5DSOGUQh/FGkMKV5VC"
            +             "UjgXQ+zRlkIAwBNDCleZQoVrEEOux5xC7FENQ5oZoUJxfQpD1yOmQnH9B0Ps0atCKdwFQz0KskLXIwRD9qi4QgrXAkNcj79Ccf0B"
            +             "Q7iexkJcjwFDpHA0Q1wPDUMpnDdDmhkNQ1K4OkMUrg1D9qg9QyncDkOuR0BDSKEQQxRuQkPD9RJDAABEQ8O1FUMAAEVDUrgYQ+F6"
            +             "RUMp3BtDj4JFQ64HH0NmJkVDFC4iQxRuREMfRSVDSGFDQ49CKEPsEUJDZiYrQx+FQEOF6y1Dcb0+Q82MMEMfxTxDrgczQ5qZOkOa"
            +             "WTVDAEA4Q+F6N0NxvTVD9mg5Q1wPM0O4HjtD4TowQ1yPPEOuRy1Dw7U9Q8M1KkMfhT5D7BEnQzPzPkNm5iND9ug+Q67HIEN7VD5D"
            +             "CtcdQ2YmPUNSOBtDuF47Q3sUGUM9CjlDj4IXQz1KNkOPghZDrkczQ64HFkNmJjBDAAAWQ+H6LEMpXBZDe9QpQ+wRF0NxvSZDuB4Y"
            +             "Q3G9I0MUbhlDmtkgQ3H9GkMKFx5Dj8IcQ8N1G0NxvR5D4foYQ/boIEP2qBZDAEAjQ66HFEMfxSVDCpcSQzNzKEPX4xBDH0UrQzNz"
            +             "D0PhOi5DzUwOQz1KMUPheg1D";

    private static final String RING_13 =
            "PQqsQntUA0MK17BCpLADQ3sUtUK43gRDcT24QsO1BkNcD7pCUvgIQwpXukLXYwtD7NG4QqSwDUNcj7VC4XoPQxQusUIfhRBDSGGs"
            +             "QlL4EENcj6dCClcRQ3G9okJSuBFDheudQgoXEkN7FJlCM3MSQ49ClEJczxJDpHCPQvYoE0OamYpCAIATQ4/ChUIK1xNDpPCAQvYo"
            +             "FEMzM3hCUngUQ+F6bkKPwhRDzcxkQq4HFUN7FFtCH0UVQylcUUJxfRVD16NHQhSuFUO4Hj5CrkcVQ3sUNkKF6xND9igwQnH9EUPh"
            +             "eixCcb0PQ2ZmK0LsUQ1Dj8ItQlL4CkP2KDRChSsJQ+xRPUJmZghDPQpHQjMzCEOPwlBCw/UHQ+F6WkIzswdD9ihkQhRuB0MK121C"
            +             "ZiYHQ1yPd0Ka2QZDuJ6AQj2KBkOkcIVCUjgGQ65HikLX4wVDuB6PQj2KBUOk8JNCpDAFQ4/CmEJ71ARDe5SdQjNzBENmZqJCXA8E"
            +             "Q1I4p0KFqwNDhWsZQxSu6UKF6xtDSGHrQq7HHUO4Hu9CUrgeQ1wP9ELhuh5DKVz5QgrXHUMpXP5CexQcQ2YmAUPDtRlDe1QCQ0gh"
            +             "F0MAAANDzYwUQ2amA0Ph+hFD7FEEQ2ZmD0Ph+gRD7NEMQ0ihBUPhOgpDH0UGQ2amB0Nm5gZDXA8FQ66HB0NSeAJDZiYIQ4/C/0KP"
            +             "wghDe5T6QilcCUNIYfVCpPAJQxQu8EKPggpD4frqQlwPC0OPwuVCCpcLQz2K4EKaGQxDcT3bQrgeDEMKV9ZC1yMLQ1yP0kI9SglD"
            +             "KVzQQkjhBkO4HtBC4ToEQ1wP0kKuxwFDCtfVQinc/0Izs9pCcb39QoXr30IUrvxC1yPlQpqZ+0IKV+pC4Xr6Qj2K70IKV/lCcb30"
            +             "QhQu+EKF6/lCAAD3Qrge/0LNzPVCZiYCQ3uU9EJxvQRDKVzzQuxRB0OaGfJC9ugJQ+zR8EJxfQxDH4XvQuwRD0NSOO5CZqYRQ0jh"
            +             "7EJSOBRDH4XrQq7HFkNcD+pC";

    private static final String RING_14 =
            "UrimQhSug0JSOK1CCteEQtcjs0KPwodCAAC4QnE9jEIKV7tCpPCRQoXrvEIKV5hCuJ68QqTwnkJSOLtChWulQpqZuUIK16tCPQq4"
            +             "Qq5HskI9irZCUri4QrgetUIzM79Cj8KzQhSuxULDdbJCFC7MQnE9sUIUrtJCXA+wQlI42UIfBa9Cj8LfQh8FrkLNTOZCAACtQgrX"
            +             "7ELNTKtCcT3zQlI4qEJ7FPlCzcyjQuH6/UKPQp5CPcoAQ8P1l0LNzAFDKVyRQvboAUOk8IpCSCEBQzMzhULh+v5C4XqAQilc+kLD"
            +             "9XlCj8L0QhSudUIAgO5C7FF0QqTw50KkcHVCClfhQuF6d0LNzNpCzcx5Qo9C1EI9CnxCcb3NQqRwfkJSOMdC4XqAQlK4wELNzIFC"
            +             "Uji6QjMzg0JxvbNC9qiEQq5HrUIULoZCCtemQq7Hh0JmZqBCw3WJQgAAmkKux4tCzcyTQmZmj0KuR45CMzOUQlK4iUIp3JlC7FGG"
            +             "QtcjoEJxPYRC4fruQuxRxkLh+vNCXI/GQkjh+ELXo8dCzcz9QhSuyEIpXAFDUrjJQlzPA0OPwspCH0UGQ+zRy0JSuAhDSOHMQoUr"
            +             "C0PD9c1CuJ4NQz0Kz0J7FBBD1yPQQh+FEkNxPdFCUvgUQylc0kKFaxdD4XrTQincGUO4ntRCXE8cQ67H1UIAwB5DpPDWQjMzIUM9"
            +             "CthCcX0jQ1wP2kL2KCVDcb3dQjPzJUPheuJCuN4lQ+F650Kk8CRDmhnsQoUrI0Oame9CPcogQ5oZ8UJcTx5De5TwQkjhG0PNTO9C"
            +             "pHAZQ7ge7kJx/RZD4frsQs2MFEMK1+tCmhkSQ1K46kJmpg9DuJ7pQjMzDUMfhehCAMAKQ8N150I9SghDZmbmQgrXBUMKV+VCSGED"
            +             "Q81M5EIU7gBDcT3jQqTw/EIzM+JCPQr4Qtcj4UK4HvNCexTgQlI47kIAAN9CuJ7pQlwP3UL2KOZCw3XZQqRw5EKux9RCe5TkQs3M"
            +             "z0Jcj+ZCUjjLQnE96kIp3MdC";

    private static final String RING_15 =
            "w7UoQwpXq0LhuipDAACsQtejLEMAgK1CuF4uQzOzr0JI4S9D4XqyQikcMUOPwrVCPQoyQ4VruULDtTJDzUy9QsM1M0OuR8FC16Mz"
            +             "Q81MxULNDDRD7FHJQjNzNEMKV81Ce9Q0Q0hh0UIULjVDhWvVQgCANUPhetlCH8U1Q1yP3UJI4TVD16PhQsO1NUNxveVCUjg1Q1K4"
            +             "6UL2aDRD4XrtQh9FM0Nm5vBCe9QxQ+zR80K4HjBDXA/2QjMzLkPhevdC9igsQ+H690JIISpDAID3QsM1KEN7FPZCcX0mQync80Ku"
            +             "ByVDw/XwQkjhI0N7lO1CHwUjQync6UJxfSJDZublQoUrIkMK1+FCmtkhQ67H3UKPgiFDcb3ZQtcjIUMUrtVCH8UgQ/ao0UK4XiBD"
            +             "uJ7NQsP1H0OamclCH4UfQ3uUxUJxPR9DH4XBQs1MH0OFa71Cw7UfQ2ZmuULheiBDmpm1QnuUIUP2KLJCjwIjQ1I4r0LhuiRD4fqs"
            +             "QoWrJkP2qKtC7JFOQ+F6nELNTFBDmpmdQh/FUUNxvZ9Ccf1SQ6RwokKPAlRDw3WlQprZVEMUrqhCrodVQ1wPrEJ7FFZDPYqvQj2K"
            +             "VkN7FLNCM/NWQ7ietkIKV1dDFC66QjOzV0OPwr1CPQpYQwpXwUIpXFhDhevEQtejWEMfhchC1+NYQ9cjzEIpHFlDj8LPQgBAWUNm"
            +             "ZtNCj0JZQz0K10KaGVlD9qjaQgDAWENxPd5C9ihYQxSu4ULNTFdDSOHkQj0KVkMAgOdCCldUQzOz6EJ7lFJDhevnQikcUUOux+VC"
            +             "UvhPQ8P14kKaGU9Dj8LfQqRwTkMpXNxCUvhNQwrX2EK4nk1Dj0LVQppZTUP2qNFCKRxNQz0KzkKa2UxDpHDKQs2MTEMK18ZCUjhM"
            +             "Q49Cw0K43ktDFK6/QnF9S0OaGbxCChdLQ1yPuEKFq0pDAAC1QuxRSkOFa7FCmhlKQ+zRrUKaGUpD9iiqQuxRSkNcj6ZCH8VKQx8F"
            +             "o0I9iktDUrifQuzRTEMULp1C";

    private static final String RING_16 =
            "uB4fQpqZ0EJcjypCHwXSQrgeNELhetVChes6Qs1M2kIAAD9CKdzfQmZmQUL2qOVCcT1DQh+F60JxPUVCKVzxQuF6R0IzM/dChetJ"
            +             "QgAA/UKamUxC12MBQ6RwT0IfRQRDH4VSQkghB0OPwlVC4foJQzMzWULs0QxDj8JcQmamD0MfhWBCw3USQ2ZmZEKPQhVDZmZoQs0M"
            +             "GEOkcGtChesaQwrXa0K43h1DUrhoQlK4IENSuGFCChcjQylcV0LheiRDmplLQrieJEPsUUBCAMAjQ+xRNkL2KCJDPQouQs0MIENc"
            +             "jydC7JEdQxSuIkK43hpDFK4eQnsUGEOF6xpCj0IVQ3E9F0KkcBJDj8ITQimcD0OkcBBCj8IMQylcDUJm5glDpHAKQq4HB0MUrgdC"
            +             "1yMEQ7geBUJxPQFDzcwCQhSu/ELXowBCCtf2Qilc/UEAAPFCCtf5Qdcj60K4HvlBUjjlQjMz/UFIYd9Cw/UCQkjh2UJI4QlCexTV"
            +             "QlyPE0JSuNFCpPDZQsN1+0LNzN9CZmb8Qo9C5UJxvf5CAADqQvYoAUMUru1CcX0DQ+H670LhOgZD16PwQhQuCUMzs+9CKRwMQxQu"
            +             "7ULNzA5DXI/pQvYoEUMUruVChWsTQ67H4UKFqxVDKdzdQoXrF0Ok8NlC9igaQx8F1kLXYxxDXA/SQrieHkOaGc5CmtkgQ9cjykJc"
            +             "DyND1yPGQq5HJUMULsJCAIAnQxQuvkLDtSlDmhm6QkjhK0NIYbVChastQ67Hr0L2qC5DKdypQlzPLkM9CqRCUjguQ7ienkIAAC1D"
            +             "CteZQnE9K0Ph+pVC4fooQ2Zmk0LNTCZDH4WSQrheI0PhepNCM3MgQ2ZmlkIp3B1DCleaQkihG0MpXJ5CFG4ZQylcokLhOhdDClem"
            +             "Qh8FFUPsUapCzcwSQ65HrkJ7lBBDUjiyQppZDkP2KLZCKRwMQ3sUukK43glDAAC+QkihB0Nm5sFCSGEFQ67HxUK4HgNDcb3JQtfj"
            +             "AEMAgM5CcT3+Qj0K1EJ7FPxC";

    private static final String RING_17 =
            "KdzmQhQur0JIYexC4XqwQkhh8UIULrNCSGH1QhQut0JcD/hCFC68Qo9C+UJxvcFC7NH4QmZmx0JmZvdCZubMQs3M9UIpXNJCcT30"
            +             "QgrX10IUrvJCzUzdQtcj8UKux+JCe5TvQo9C6EI9Cu5Ccb3tQgCA7EJSOPNCw/XqQjOz+EKkcOlCFC7+QqTw50J71AFDw3XmQnuU"
            +             "BENm5uRC7FEHQ5qZ4kL26AlD4freQgoXDEOPQtpCZqYNQ+zR1ELheg5D9ijPQlyPDkPXo8lC9ugNQ/aoxEI9igxD9qjAQq6HCkPh"
            +             "+r1CrgcIQ4/CvEKPQgVD1yO9QhRuAkMAgL5CClf/Qj0KwEIp3PlCe5TBQkhh9EKaGcNCZubuQriexEJmZulC9ijGQoXr40Izs8dC"
            +             "pHDeQnE9yULh+thCzczKQgCA00IpXMxCHwXOQmbmzUI9ishC4XrPQnsUw0IfBdFCmpm9Qilc00KFa7hCAADXQnsUtEKPwttCAACx"
            +             "QhQu4UIKV69CuF4bQ65HnUI9Ch5DzUyeQuF6IENxvaBCrociQ+xRpEJ7VCRDpHCoQpoZJkOamaxCmtknQ83MsEJ7lClDHwW1Qj1K"
            +             "K0OuR7lC4fosQ1yPvUJmpi5DSOHBQs1MMENxPcZChesxQ9ejykKPgjNDexTPQlwPNUN7lNNC7JE2Q9cj2EKuBzhDcb3cQjMzOUP2"
            +             "qOFCH8U5QwAA50LhujlDw3XsQuwROUNxvfFCzcw3Q1yP9kKk8DVDH4X6QuyRM0MzM/1C1+MwQ1wP/kJxPS5DKdz8QnH9K0MK1/lC"
            +             "j0IqQ5qZ9UIAwChDXA/xQlI4J0M9iuxC9qglQ1wP6ELsESRDuJ7jQjNzIkMzM99C7NEgQ+zR2kL2KB9DAIDWQlJ4HUMzM9JCj8Ib"
            +             "Q6TwzUKuBxpDM7PJQq5HGEMfhcVCj4IWQylcwUJxvRRDMzO9Qj1KE0N7lLhCcX0SQ0hhs0IpXBJDpPCtQqTwEkO4nqhC4ToUQ+zR"
            +             "o0KkMBZDPQqgQvaoGEOux51C";

    private static final String RING_18 =
            "hWu5QuF6mEIfhb9CFC6ZQsN1xUIzs5pCuB7LQlwPnUKFa9BC9iigQo9C1UKF66NCXI/ZQuxRqEJSON1CcT2tQhQu4EK4nrJCSGHi"
            +             "QgpXuELs0eNCzUy+QuF65EJmZsRCKVzkQj2KykLDdeNCuJ7QQs3M4UIfhdZCZmbfQhQu3ELNTNxCw3XhQlyP2EIKV+ZCcT3UQjOz"
            +             "6kKFa89C4XruQvYoykL2qPFCPYrEQtcj9EL2qL5CZub1QpqZuEJI4fZCw3WyQh8F90IpXKxCClf2QoVrpkLs0fRCj8KgQsN18kLD"
            +             "dZtCSGHvQrielkKametC7FGSQlI450L2qI5CrkfiQlK4i0Jm5txCAICJQhQu10I9CohCUjjRQmZmh0K4HstCH4WHQuH6xEKkcIhC"
            +             "heu+QpoZikIAALlCAICMQilcs0KamY9CXA+uQgpXk0IULqlC9qiXQuzRpELhepxCPQqhQnG9oUIp3J1CClenQkhhm0JSOK1CuJ6Z"
            +             "Qq5Hs0LXo5hC4bodQ/YozEJcTx9Dj0LMQincIEMK18xCmlkiQ2bmzUJxvSNDSGHPQo8CJUNxPdFCZiYmQ6Rw00JIISdDZubVQsP1"
            +             "J0N7lNhCSKEoQ6Rw20JIISlDpHDeQjNzKUMfheFCCpcpQxSu5ELNjClD7NHnQlxPKUOk8OpCSOEoQ+H67UKuRyhDZubwQh+FJ0P2"
            +             "qPNCe5QmQzMz9kLheiVDw3X4QgBAJEOkcPpC1+MiQz0K/EIUbiFDj0L9QvboH0M9Cv5CClceQ2Zm/kKPwhxDrkf+QsM1G0Mzs/1C"
            +             "4boZQ/ao/EJ7VBhDFC77QlwPF0PNTPlCFO4VQ7ge90Kk8BRD9qj0QikcFEPh+vFCpHATQ7ge70Iz8xJDuB7sQrieEkMfBelC4XoS"
            +             "Q0jh5UIfhRJDcb3iQo/CEkOamd9CFC4TQ1yP3EKuxxND9qjZQlyPFENm5tZCAIAVQylc1EIKlxZDmhnSQnvUF0PXI9BCFC4ZQx+F"
            +             "zkLXoxpDzUzNQoUrHEMfhcxC";

    private static final String RING_19 =
            "hetnQlwP4EI9CnRCH4XgQq5Hf0Ls0eJCpHCEQj2K5kIULohCClfrQlK4ikJI4fBCrkeMQq7H9kKuR41Crsf8QtcjjkL2aAFD9iiP"
            +             "QoVrBEPNTJBC9mgHQx+FkUJmZgpDzcySQkhhDUMULpRCmlkQQ9ejlUJcTxNDFC6XQo9CFkMK15hCMzMZQ5qZmkK4HhxD7FGcQj0K"
            +             "H0MpXJ1CPQoiQwrXnELsESVDXI+aQkjhJ0MAgJZCSCEqQ3sUkUJxfStDPQqLQrjeK0MfBYVCpHArQ4/CfkLsUSpD16N0QimcKEO4"
            +             "HmxCpHAmQx+FZULX4yNDhetgQlwPIUMpXF1CZiYeQ7geWkLDNRtDhetWQh9FGENI4VNCXE8VQwAAUUKaWRJDrkdOQkhhD0NSuEtC"
            +             "ZmYMQylcSUL2aAlDuB5HQvZoBkM9CkVCZmYDQ7geQ0LXYwBD4XpBQnG9+kLhekFC9qj0QnE9REJSuO5Cj8JJQs1M6UJI4VFCrsfk"
            +             "QvYoXEJcj+FCZubqQoXrykLXI/FCFK7LQsP19kI9Cs5C4fr7QuzR0UKk8P9CM7PWQgBAAUPDddxCzQwCQz2K4kKPwgJDFK7oQsN1"
            +             "A0Ps0e5CZiYEQ+H69EKa2QRDuB77Qs2MBUNIoQBDcT0GQ8O1A0MU7gZDrscGQymcB0Mp3AlD7FEIQxTuDEOPAglDAAAQQ/aoCUMK"
            +             "FxNDuN4JQ1I4FkMAQAlDzUwZQ3vUB0OaGRxD4boFQzNzHkNmJgND4TogQ1I4AENIYSFDUjj6QnvUIUPD9fNCAIAhQ5oZ7kK4XiBD"
            +             "PQrpQo+CHkPXI+VCzQwcQ3G94kLXIxlDrkfhQnsUFkNm5t9CAAATQz2K3kIU7g9D9ijdQprZDEOux9tCrscJQ2Zm2kIzswZDHwXZ"
            +             "QkihA0O4ntdCXI8AQ3E91kLD9fpCCtfUQuzR9EKFa9NCFK7uQj0K0kI9iuhC9qjRQo9C4kIK19JCexTcQnuU1UKkcNZCFK7ZQvao"
            +             "0ULs0d5CPQrOQvao5EJxvctC";

    private static final String RING_20 =
            "AIDjQvYo8ELsUexCXI/xQrie9EIp3PRCw/X7QoXr+UKk8ABDPUoAQx8FA0PhOgRDuB4EQ1yPCEOaGQRDHwUNQ/YoA0NIYRFDpPAB"
            +             "QxSuFUOksABD4foZQ0jh/kIfRR5DSGH8QlyPIkMK1/lCmtkmQ65H90LXIytDM7P0QoVrL0OaGfJCpLAzQx+F70JS+DdDhevsQgBA"
            +             "PEOaGepCcX1AQxSu5UIpXERDpPDeQh9FR0MK19ZCuB5JQ3sUzkJx/UlD9ijFQlL4SUMpXLxCpDBJQ6Tws0Izs0dDMzOsQuF6RUOa"
            +             "maVC4XpCQ+zRoEJSuD5DuJ6eQvZoOkNxvZ9CAAA2Q+F6okJxvTFD1yOlQlJ4LUOPwqdCMzMpQ0hhqkKF6yRDw/WsQtejIEMAgK9C"
            +             "KVwcQz0KskLsERhDXI+0Qq7HE0NcD7dCcX0PQz2KuUKkMAtDpPC7QkjhBkMAAL9CpLACQyncw0Jm5v1C7FHKQnG990I9CtJCrkfz"
            +             "QpqZ2kJSuPBCpDA1Q4Vr/0JmZjlDrgcAQ+E6PUM9ygFDpDBAQ83MBENcD0JDCpcIQ7jeQkNxvQxDj8JCQ+H6EEPDNUJDFC4VQ82M"
            +             "QUO4XhlDPcpAQ82MHUP26D9DpLAhQ/boPkPs0SVDzcw9Q/boKUPskTxDUvgtQ1I4O0Nx/TFDUrg5Q8P1NUPsEThDuN45Q49CNkMz"
            +             "sz1Dj0I0Q6RwQUOa2TFDhetEQxTuLkNx/UdDuJ4rQ9ejSkMAAChDmtlMQ0ghJEMKl05D7BEgQ67HT0Mp3BtDcT1QQ6SwF0OamU9D"
            +             "XE8UQ/YoTUN7VBNDKRxJQ+F6FENcD0VDZmYWQ65HQUNxPRhDw3U9QxTuGUNcjzlD4XobQymcNUPX4xxDuJ4xQ4UrHkPskS1D7FEf"
            +             "Q3F9KUOaWSBDSGElQ49CIUPhOiFDXA8iQ+wRHUMzsyJDuN4YQz1KI0OFqxRDuB4kQx+FEEPDdSVDAIAMQ3F9J0OuxwhD9igqQ3F9"
            +             "BUO4Xi1D4boCQ3sUMUMzswBD";

    private static final String RING_21 =
            "UrhRQo/C30I9Cl9CmpngQgAAbEKFa+JC7FF4Qtcj5UKF64FCmpnoQlI4h0JSuOxCHwWMQoVr8ULsUZBCmpn2Qj0KlEIzM/xCUjiX"
            +             "QlwPAUPNzJlChSsEQ67Hm0JIYQdDuB6dQhSuCkOux51CHwUOQ1K4nULXYxFDZuacQlK4FEOPQptC4foXQ67HmEIpHBtD4XqVQq4H"
            +             "HkMKV5FCFK4gQ2ZmjELD9SJDj8KGQj3KJENcj4BCexQmQwAAdEKuxyZDXI9mQvboJkMzM1lCcX0mQ65HTELskSVDw/U/QlI4JEOk"
            +             "cDRCcX0iQwrXKUKFayBDMzMgQuwRHkPXoxdC4XobQ/YoEEIUrhhDCtcJQlK4FUPXowRCuJ4SQxSuAEJmZg9DexT8QSkcDEOkcPlB"
            +             "j8IIQxSu+UFmZgVDw/X8QVwPAkOPwgFCmpn9QhSuBkIpXPdC7FENQgCA8UKamRVCMzPsQqRwH0LXo+dCUrgqQgAA5EL2KDdChWvh"
            +             "Qq5HREIfBeBCrsf9QkhhvkJcjwJDSOG+QvYoBkMKV8BC16MJQ1K4wkKk8AxD4frFQuH6D0O4HspC4boSQwAAz0JIIRVDe5TUQtcj"
            +             "F0NxvdpCUrgYQ0hh4UIp3BlDSGHoQj2KGkOame9Ccb0aQ6Tw9kIzcxpDrkf+QoWrGUNxvQJDFG4YQzMzBkOPwhZD4XoJQ6SwFEOP"
            +             "ggxDAEASQx9FD0Pheg9DpLARQxRuDEMAwBND1yMJQ9djFUP2qAVDCpcWQ1wPAkPsURdDrsf8QlyPF0OkcPVCXE8XQ1I47kIKlxZD"
            +             "j0LnQmZmFUMUruBCj8ITQ3uU2kIzsxFDexTVQo9CD0OuR9BCUngMQ49CzELXYwlDmhnJQuwRBkPs0cZC7JECQ8N1xUKF6/1CXA/F"
            +             "QlyP9kL2qMVCcT3vQjMzx0I9CuhCFK7JQrge4UIfBc1CXI/aQhQu0UIAgNRCXA/WQuH6zkJ7lNtC1yPKQhSu4UIfBcZCj0LoQlK4"
            +             "wkJSOO9C7FHAQqRw9kIp3L5C";

    private static final String RING_22 =
            "FK6QQvaonkIp3JVCrkefQj0Km0JI4Z9CcT2gQgCAoEKFa6VC1yOhQpqZqkLs0aFCj8KvQh+FokKk8LRCj0KjQpoZukIAAKRCj0K/"
            +             "Qq7HpEKFa8RCe5SlQnuUyUKFa6ZCcb3OQo9Cp0JI4dNC1yOoQh8F2UI9CqlCFC7eQgrXqULNTONCSOGqQuH650K4Hq1CAIDrQqTw"
            +             "sEIpXO1Crse1Qtcj7ULD9bpCpPDqQvaov0LXI+dCMzPDQuxR4kK4HsVCuB7dQvYoxUIAANhCMzPEQinc0kKuR8NCM7PNQkhhwkJc"
            +             "j8hCH4XBQmZmw0L2qMBCcT2+Qincv0J7FLlCXA+/QoXrs0KuR75Cj8KuQj2KvUJ7lKlCzcy8QmZmpEK4HrxCcT2fQqRwu0I9CppC"
            +             "7NG6QinclEJSOLpCFK6PQrieuULheopCAAC5QlyPhUJIYbdC4fqBQvaos0IAgIBCM7OuQgAAgUIfhalC9iiDQq7HpEKPwoZCXA+h"
            +             "QuF6i0Jm5p5Ce9QIQwCAtEJ7VAtDhWu1Qs3MDUMUrrZCrkcQQynct0IAwBJDXA+5QlI4FUOuR7pCpLAXQz2Ku0JmJhpDzcy8Qrie"
            +             "HEOaGb5CexQfQ4Vrv0KuhyFDj8LAQuH6I0PXI8JCFG4mQx+Fw0JI4ShDpPDEQuxRK0NmZsZCAMAtQ0jhx0KkMDBDClfJQnF9MkPh"
            +             "estCFC40Q3E9z0K4HjVDw/XTQlxPNUMfBdlCpLA0Q4Xr3ULDNTNDHwXiQuH6MENIYeRCw3UuQ49C5EIfBSxD7NHiQnuUKUPsUeFC"
            +             "1yMnQync30IzsyRDpHDeQgBAIkM9Ct1CzcwfQxSu20IKVx1DClfaQkjhGkM9CtlChWsYQ4/C10Iz8xVDH4XWQuF6E0OuR9VCjwIR"
            +             "Q1wP1EI9ig5DCtfSQlwPDEO4ntFCCpcJQ4Vr0EK4HgdDUjjPQq7HBENxPc1Cw/UCQ1K4yUIU7gFDmhnFQh/FAUM9CsBCcX0CQxQu"
            +             "u0JcDwRDUji3QlxPBkOF67RC";

    private static final String RING_23 =
            "mhnWQkhh6EIfhdxChWvpQh+F4kIAAOxCM7PnQqTw70IK1+tC4fr0QnuU7kJm5vpCexTwQkihAEN7FPFCKdwDQ3sU8kIKFwdDexTz"
            +             "QlxPCkO4HvRCPYoNQxQu9UIfxRBDcT32QnH9E0MKV/dCwzUXQ6Rw+EIUbhpDmpn5QmamHUPNzPpCKdwgQ2bm+0J7FCRDexT8QgpX"
            +             "J0NmZvpCcX0qQ1wP90KuRy1DrkfyQnF9L0MfhexCHwUxQ1I45kK43jFDM7PfQj0KMkOPQtlCXI8xQ/Yo00L2aDBDcb3NQnuULkOk"
            +             "cMlC1yMsQzOzxkIULilDUjjFQgAAJkN7FMRCrsciQ6TwwkLskR9D7NHBQppZHENSuMBCSCEZQ/aov0Jm5hVDmpm+QhSuEkNcj71C"
            +             "M3MPQz2KvEJSOAxDH4W7QgAACUMfhbpCH8UFQ9ejuUKuhwJDheu5Qj2K/kJxvbtCzUz4QpoZv0Izs/JCj8LDQtcj7kIpXMlC7NHq"
            +             "QnuUz0JI4ehCwzU9Q4Xr80IpXD9DhWv1Qs3MQEMAAPlC9mhBQ8N1/ULsUUFDPQoBQwqXQEPhOgNDUjg/QwoXBUOuRz1DClcGQ7ge"
            +             "O0OkMAdDM/M4QwAACEOuxzZDe9QIQymcNENIoQlDFG4yQxRuCkNxPTBDMzMLQ80MLkMz8wtDmtkrQ6SwDEPXoylDZmYNQxRuJ0MK"
            +             "Fw5DwzUlQ4/CDkPh+iJDhWsPQwDAIEPNDBBDj4IeQ/aoEEMfRRxDAEARQx8FGkNczxFDUrgXQ+H6EUPskRVDFC4RQ48CFENxfQ9D"
            +             "AEATQ1xPDUNcTxNDAAALQ/YoFEMp3AhDM7MVQ9cjB0NxvRdDzQwGQ1L4GUMUbgVDwzUcQwrXBEMzcx5DUjgEQxSuIEMKlwND9ugi"
            +             "QxTuAkNIISVDj0ICQwpXJ0N7lAFDzYwpQ7jeAEOPwitDZiYAQzPzLUPs0f5CZiYwQ81M/UJ7VDJDcb37Qo+CNEP2KPpCFK42Qx+F"
            +             "+EIK1zhD7NH2QuH6OkNcD/VC";

    private static final String RING_24 =
            "cT3FQlwP80KkcMlCMzPzQlyPzUJcD/RCpHDRQvao9ULh+tRCpPD3Qh8F2EIK1/pC4XraQnE9/kLNTNxCHwUBQ+xR3ULNDANDXI/d"
            +             "QmYmBUNxPd1CAEAHQxSu3EJ7VAlDexTcQoVrC0MfhdtCAIANQ+H62kIKlw9DhWvaQhSuEUMK19lCj8ITQzMz2UIK1xVD9ijYQkjh"
            +             "F0MpXNZCrscZQwrX00IzcxtDFK7QQnvUHEMfBc1CKdwdQx8FyUIfhR5DKdzEQlzPHkP2qMBCUrgeQ1yPvEIfRR5D9qi4QlJ4HUPX"
            +             "I7VCe1QcQ1wPskLX4xpDXI+vQsM1GUMzs61C7FEXQ3uUrELNTBVDj0KsQjMzE0Ncj6xCKRwRQ9cjrUIfBQ9DzcytQqTwDENmZq5C"
            +             "KdwKQ+H6rkKuxwhDe5SvQqSwBkP2KLBCKZwEQ83MsEKuhwJDheuxQgCAAEOPwrNCUjj9Qs1MtkJI4flCw3W5Qrge90K4Hr1CPQr1"
            +             "QnsUwUIUrvNCXE8bQ3uU/kIpXB1DKdz+QntUH0Ph+v9C1yMhQ8P1AEMzsyJDPUoCQzPzI0OF6wNDCtckQx/FBUOaWSVDAMAHQ3F9"
            +             "JUPNzAlDmlklQ5rZC0PsESVD1+MNQz3KJEOF6w9DrockQ8P1EUOPQiRDAAAUQ1L4I0M9ChZDZqYjQ1wPGEMfRSNDChcaQzOzIkNc"
            +             "DxxDe9QhQ4XrHUMUriBDuJ4fQz1KH0NIISFDpLAdQ/ZoIkNm5htDhWsjQ1L4GUOaGSRDpPAXQ/ZoJEPX4xVD7FEkQ2bmE0Ps0SND"
            +             "XA8SQ4XrIkNSeBBDuJ4hQ3E9D0Ph+h9D9mgOQ5oZHkMAAA5DChccQ48CDkM9ChpD7FEOQ48CGEPXow5D4foVQ8P1DkMz8xNDAEAP"
            +             "Q4XrEUOPgg9DSOEPQ+G6D0N71A1DUvgPQz3KC0MfRRBDj8IJQ3G9EEOPwgdDrocRQ7jeBUO4nhJDSCEEQ+H6E0MKlwJD7JEVQ65H"
            +             "AUOaWRdDH0UAQ65HGUP2KP9C";

    private static final String[] RING_DATA = {
            RING_00,
            RING_01,
            RING_02,
            RING_03,
            RING_04,
            RING_05,
            RING_06,
            RING_07,
            RING_08,
            RING_09,
            RING_10,
            RING_11,
            RING_12,
            RING_13,
            RING_14,
            RING_15,
            RING_16,
            RING_17,
            RING_18,
            RING_19,
            RING_20,
            RING_21,
            RING_22,
            RING_23,
            RING_24
    };

    // ---------------- 身体轮廓 ----------------
    public static final String[] SHAPE_KEYS = { "blob", "wedge", "gem" };

    /** 身体形状 blob 的轮廓（96 点） */
    private static final String SHAPE_BLOB =
            "PYpkQz2K5EI9SmRD4XrzQs2MY0MULgFD7FFiQ1yPCEO4nmBDCtcPQzNzXkNx/RZD7NFbQ3H9HUPhulhDzcwkQzMzVUPXYytDAEBR"
            +             "Q+G6MUPX40xDXM83Q2YmSEMKlz1DPQpDQz0KQ0MKlz1DZiZIQ1zPN0PX40xD4boxQ3E9UUNIYStDpDBVQ67HJEMzs1hDUvgdQ67H"
            +             "W0Ph+hZDZmZeQ+zRD0Ncj2BDPYoIQwBAYkOFKwFDw3VjQ+F680KkMGRDPYrkQqRwZEOamdVCMzNkQ3G9xkLhemND4fq3Qh9FYkOF"
            +             "a6lCe5RgQ7gem0KFa15DuB6NQj3KW0MAAH9Cw7VYQ9ejZEIULlVDcT1LQnE9UUPD9TJC1+NMQwrXG0JmJkhDAAAGQj0KQ0O4HuNB"
            +             "Cpc9QzMzvUFczzdD7FGaQeG6MUOamXVBSGErQ7gePUE9yiRDFK4LQXH9HUNI4cJAcf0WQ0jhekAK1w9DpHANQFyPCEOkcH0/FC4B"
            +             "QwAAgD7hevNCAAAAAD2K5EIAAIA+mpnVQkjhej9SuMZCzcwMQMP1t0KamXlAZmapQoXrwUB7FJtCPQoLQXsUjULhejxBhet+Qs3M"
            +             "dEEfhWRCAACaQbgeS0JI4bxBzcwyQs3M4kEUrhtCCtcFQgrXBULXoxtCUrjiQY/CMkJSuLxBexRLQoXrmUHhemRC16N0QUjhfkLs"
            +             "UTxBexSNQj0KC0F7FJtCcT3CQGZmqUKamXlAw/W3QqRwDUBSuMZCAACAP5qZ1UK4HoU+PYrkQgrXIzzhevNCAACAPqQwAUNI4Xo/"
            +             "7JEIQ/YoDECa2Q9D7FF4QI8CF0OuR8FAjwIeQ0jhCkHs0SRD9ig8QYVrK0PXo3RBH8UxQ4XrmUEK1zdDzcy8QbiePUPNzOJBexRD"
            +             "QwrXBUIzM0hDFK4bQqTwTEPNzDJCPUpRQ7geS0LhOlVDH4VkQo/CWEOF635CCtdbQ3sUjULhel5DexSbQmamYENmZqlCCldiQ8P1"
            +             "t0Jcj2NDUrjGQj1KZEOamdVC";
    /** blob 的五官拟合参数：{ x, y, sx, sy, eye } */
    public static final float[] FACE_BLOB = { 0f, 0f, 1f, 1f, 1f };
    /** blob 的倾斜缩放系数 */
    public static final float TILT_BLOB = 1f;

    /** 身体形状 wedge 的轮廓（96 点） */
    private static final String SHAPE_WEDGE =
            "UvhAQz2K5EJcD0RDj0LvQqRwR0PD9fpCFC5LQzPzA0O4Xk9DUjgLQ2YmVENxfRNDZqZZQwoXHUMAAF9D1+MnQ48CY0MpXDND4Xpk"
            +             "Q3uUPkNSeGJDuF5IQ48CXUNI4U9DjwJVQ48CVUMKl0tDuB5YQ8O1QUM9yllDSOE3QzNzWkPDdS5DrodaQ6SwJUOuh1pDw3UdQ66H"
            +             "WkP2qBVDrodaQ8M1DkOuh1pDjwIHQ66HWkMAAABDrodaQzMz8kKuh1pDPYrkQq6HWkNI4dZCrodaQ3sUyUKuh1pDXA+7Qq6HWkP2"
            +             "qKxCrodaQ4/CnUKuh1pD9iiOQq6HWkOkcHtCrodaQ+xRWEKuh1pD16MyQjNzWkPsUQtCPcpZQ5qZx0G4HlhD4Xp4QY8CVUPD9fBA"
            +             "SOFPQ+F6BEApXEhDj8J1PXuUPkO4HsU/KVwzQ65HsUDX4ydDZmYuQQoXHUO4HoNBAIATQylcqUFSOAtDSOHKQTPzA0PNzOhBw/X6"
            +             "QoXrAUKPQu9CrkcOQj2K5EKPwhlCmpnaQh+FJEKuR9FCUrguQsN1yELhejhCHwXAQkjhQUIp3LdCPQpLQoXrr0I9ClRCuB6oQgAA"
            +             "XUJIYaBChetlQtejmEKF625CCteQQnsUeEJm5ohCj8KAQo/CgEK4noVC16NwQlK4ikLD9V5CmhmQQuxRTEIK15VCpHA4Qj0KnEJ7"
            +             "FCNCAACjQpqZDEKk8KpCpHDrQT0KtEJxPb5BpHC+QnsUlEH2KMpCpHBhQR8F10KamTFBPYrkQvYoIEFcD/JCmpkxQYXr/kKkcGFB"
            +             "7FEFQ3sUlEEfhQpDcT2+QewRD0OkcOtBPQoTQ5qZDEIfhRZDexQjQrieGUOkcDhCcX0cQ+xRTEIULh9Dw/VeQuG6IUPXo3BC9igk"
            +             "Q4/CgEIfhSZDZuaIQlzPKEMK15BCXA8rQ9ejmEI9Si1DSGGgQq6HL0O4HqhCrscxQ4Xrr0LsETRDKdy3QoVrNkMfBcBCKdw4Q8N1"
            +             "yEL2aDtDrkfRQpoZPkOamdpC";
    /** wedge 的五官拟合参数：{ x, y, sx, sy, eye } */
    public static final float[] FACE_WEDGE = { 0f, 24f, 0.7f, 0.7f, 0.79f };
    /** wedge 的倾斜缩放系数 */
    public static final float TILT_WEDGE = 0.22f;

    /** 身体形状 gem 的轮廓（96 点） */
    private static final String SHAPE_GEM =
            "4XpjQz2K5EJxPWJDUjjzQs0MYENSuABD7FFdQ1yPB0MzM1pDuB4OQ83MVkPXYxRDZiZTQ2ZmGkN7VE9D9iggQwpXS0MzsyVDpDBH"
            +             "Q48CK0OF60JDZiYwQ+F6PkOaGTVD9ug5Q/boOUP2KDVDzYw+Q49CMENcD0NDhSsrQ4VrR0O43iVDZqZLQylcIEPhuk9DKZwaQ2am"
            +             "U0OamRRD12NXQ1xPDkNm5lpDUrgHQ7geXkMK1wBDpPBgQwpX80IzM2NDPYrkQnF9ZENxvdVCMzNjQ2Zmx0Kk8GBD16O5QrgeXkPh"
            +             "eqxCZuZaQ2bmn0LXY1dDSOGTQmamU0MpXIhC4bpPQxSuekJmpktD4XplQhRuR0O4HlFCXA9DQx+FPULNjD5DXI8qQvboOUNxPRhC"
            +             "mhk1Q+F6BkJmJjBDzczqQY8CK0OamclBpLAlQ4/CqUH2KCBDuB6LQWZmGkMK11tBZmYUQ6RwJUG4Hg5DKVznQFyPB0MUro9AUrgA"
            +             "QzMzE0BSOPNCFK6HPz2K5EIzMxNAKdzVQhSuj0DXo8dCKVznQMP1uUKkcCVBCtesQgrXW0HNTKBCuB6LQa5HlEKPwqlBj8KIQpqZ"
            +             "yUFmZntCzczqQbgeZkIfhQZCXI9RQnE9GEKPwj1CXI8qQlyPKkIfhT1Cw/UXQrgeUUKF6wVC4XplQsP16EEUrnpCuB7HQSlciELh"
            +             "eqZBSOGTQrgeh0FI4Z9CZmZSQeF6rEJxPRpB16O5QqRwzUBmZsdCZmZmQHG91UIfhas/PYrkQs3MTD0KV/NCH4WrPwrXAENmZmZA"
            +             "UrgHQ6RwzUBcTw5DcT0aQZqZFENmZlJBKZwaQ7geh0EpXCBD4XqmQbjeJUMzM8dBhSsrQ8P16EGPQjBDhesFQvYoNUPD9RdC9ug5"
            +             "Q1yPKkLhej5Dj8I9QvboQkNcj1FCpDBHQ7geZkIKV0tDZmZ7QntUT0OPwohCZiZTQ65HlELNzFZDzUygQjMzWkMK16xC7FFdQ8P1"
            +             "uULNDGBD16PHQnE9YkMp3NVC";
    /** gem 的五官拟合参数：{ x, y, sx, sy, eye } */
    public static final float[] FACE_GEM = { 0f, 0f, 0.89f, 0.89f, 0.99f };
    /** gem 的倾斜缩放系数 */
    public static final float TILT_GEM = 1f;

    private static final String[] SHAPE_DATA = { SHAPE_BLOB, SHAPE_WEDGE, SHAPE_GEM };
    public static final float[][] FACE_DATA = { FACE_BLOB, FACE_WEDGE, FACE_GEM };
    public static final float[] TILT_DATA = { TILT_BLOB, TILT_WEDGE, TILT_GEM };

    /** 解码结果缓存：25 组眼环 / 3 种身体。每帧都要取，不能每次都解一遍 Base64 */
    private static final float[][] RING_CACHE = new float[EXPRESSION_COUNT][];
    private static final float[][][] RING_SPLIT = new float[EXPRESSION_COUNT][2][];
    private static final float[][] SHAPE_CACHE = new float[SHAPE_DATA.length][];

    /** 解码：float32 小端 Base64 -> float[] */
    private static float[] decode(String b64) {
        byte[] raw = Base64.decode(b64, Base64.DEFAULT);
        FloatBuffer fb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] out = new float[fb.remaining()];
        fb.get(out);
        return out;
    }

    /**
     * 取第 index 组眼环的轮廓点。
     *
     * <p>首次取用才解码，之后走缓存 —— 每帧都要取，不能每次都 Base64 解一遍。</p>
     *
     * @param index 眼环组索引 0..24
     * @param eye   0 = 左眼，1 = 右眼
     * @return 长度 RING_POINTS*2 的数组，按 [x0,y0,x1,y1,...] 排布
     */
    public static float[] ring(int index, int eye) {
        float[] pair = RING_CACHE[index];
        if (pair == null) {
            pair = decode(RING_DATA[index]);
            RING_CACHE[index] = pair;
        }
        int n = RING_POINTS * 2;
        float[] out = RING_SPLIT[index][eye];
        if (out == null) {
            out = new float[n];
            System.arraycopy(pair, eye * n, out, 0, n);   // 2 眼连续存放
            RING_SPLIT[index][eye] = out;
        }
        return out;
    }

    /** 取身体轮廓（96 点，[x0,y0,...]），同样带缓存 */
    public static float[] shape(int which) {
        float[] v = SHAPE_CACHE[which];
        if (v == null) { v = decode(SHAPE_DATA[which]); SHAPE_CACHE[which] = v; }
        return v;
    }

    private Rings() { }
}
